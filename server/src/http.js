import express from 'express';
import { config, RULES } from './config.js';
import { HttpError } from './errors.js';
import { requestOtp, verifyOtp, guestLogin, requireAuth, publicUser, logout } from './auth.js';
import { deposit, requestWithdrawal, walletSummary } from './wallet.js';
import { addFriendByPhone, removeFriend, listFriends, leaderboard } from './friends.js';
import { gameHistory, fairnessProof } from './gameStore.js';
import { presence } from './socket.js';
import { db } from './db.js';

export function createApp() {
  const app = express();
  app.use(express.json({ limit: '20kb' }));

  app.get('/api/health', (_req, res) => res.json({ ok: true, devMode: config.devMode }));
  app.get('/api/rules', (_req, res) => res.json(RULES));

  app.post('/api/auth/otp', (req, res) => res.json(requestOtp(req.body.phone)));
  app.post('/api/auth/verify', (req, res) => res.json(verifyOtp(req.body)));
  app.post('/api/auth/guest', (req, res) => res.json(guestLogin(req.body)));

  app.use('/api', requireAuth);

  app.post('/api/auth/logout', (req, res) => {
    logout(req.token);
    res.json({ ok: true });
  });

  app.get('/api/me', (req, res) => res.json(publicUser(req.user)));
  // POST as well as PATCH: Android's HttpURLConnection has no PATCH.
  app.post('/api/me', (req, res) => updateName(req, res));
  app.patch('/api/me', (req, res) => updateName(req, res));
  const updateName = (req, res) => {
    const name = String(req.body.name || '').trim().slice(0, 20);
    if (name.length < 2) throw new HttpError(400, 'Name must be 2-20 characters');
    db.prepare('UPDATE users SET name = ? WHERE id = ?').run(name, req.user.id);
    res.json(publicUser({ ...req.user, name }));
  };

  app.get('/api/wallet', (req, res) => res.json(walletSummary(req.user)));
  app.post('/api/wallet/deposit', (req, res) => {
    if (!config.devMode) {
      // Production: create a payment-gateway order here (Razorpay/Cashfree/PhonePe) and
      // credit the wallet only from the gateway's verified webhook - never from the client.
      throw new HttpError(501, 'Payment gateway not configured');
    }
    deposit(req.user.id, req.body.amountRs, 'Wallet top-up (dev mode)');
    res.json(walletSummary(req.user));
  });
  app.post('/api/wallet/withdraw', (req, res) => {
    requestWithdrawal(req.user.id, req.body.amountRs);
    res.json(walletSummary(req.user));
  });

  app.get('/api/friends', (req, res) => res.json(listFriends(req.user.id, presence)));
  app.post('/api/friends', (req, res) => {
    addFriendByPhone(req.user.id, req.body.phone);
    res.json(listFriends(req.user.id, presence));
  });
  app.delete('/api/friends/:id', (req, res) => {
    removeFriend(req.user.id, req.params.id);
    res.json(listFriends(req.user.id, presence));
  });

  app.get('/api/leaderboard', (req, res) => res.json(leaderboard(req.user.id)));
  app.get('/api/games', (req, res) => res.json(gameHistory(req.user.id)));
  app.get('/api/games/:id/fairness', (req, res) => {
    const proof = fairnessProof(req.params.id);
    if (!proof) throw new HttpError(404, 'Game not found');
    res.json(proof);
  });

  // Express 5 forwards thrown errors (sync and async) here.
  app.use((err, _req, res, _next) => {
    if (err instanceof HttpError) return res.status(err.status).json({ error: err.message, ...err.extra });
    if (err.type === 'entity.parse.failed') return res.status(400).json({ error: 'Invalid JSON' });
    console.error(err);
    res.status(500).json({ error: 'Something went wrong' });
  });

  return app;
}
