import type { NextFunction, Request, Response } from "express";

/**
 * Minimal fixed-window rate limiter (master §10.6 "enforce rate limits").
 *
 * This is a backstop against a compromised or bugged caller, not public-
 * traffic protection — the only expected callers are glamli-api/glamli-worker
 * inside the same Container Apps Environment (internal ingress is the primary
 * access control; see infrastructure/modules/container-app-weka-mcp.bicep).
 * A small hand-rolled counter is deliberately used here instead of pulling in
 * express-rate-limit as a dependency for a single low-traffic internal route.
 */
export function rateLimit(opts: { windowMs: number; max: number }) {
  const hits = new Map<string, { count: number; windowStart: number }>();

  return (req: Request, res: Response, next: NextFunction) => {
    const key = req.ip ?? "unknown";
    const now = Date.now();
    const entry = hits.get(key);

    if (!entry || now - entry.windowStart >= opts.windowMs) {
      hits.set(key, { count: 1, windowStart: now });
      next();
      return;
    }

    entry.count += 1;
    if (entry.count > opts.max) {
      res.status(429).json({ error: "rate_limited", retryAfterMs: opts.windowMs - (now - entry.windowStart) });
      return;
    }
    next();
  };
}
