import { readFile, writeFile } from "node:fs/promises";
import { basename } from "node:path";

/**
 * Error carrying the Weka API's enriched error body (master §10.5), so tool
 * handlers can surface the API's own `code` and recovery metadata
 * (`userMessage`, `recoverable`, `allowedRecoveries`) rather than a generic HTTP
 * failure. `userMessage`/`recoverable`/`allowedRecoveries` are optional so an
 * older API that only returns `{error, code}` still works.
 */
export class WekaApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly userMessage?: string,
    public readonly recoverable?: boolean,
    public readonly allowedRecoveries?: string[],
  ) {
    super(message);
    this.name = "WekaApiError";
  }

  /** Human/agent-readable one-liner (fallback for non-structured consumers). */
  toString(): string {
    return `Weka API error ${this.status} ${this.code}: ${this.message}`;
  }

  /** The enriched error shape (master §10.5) for structured downstream parsing. */
  toStructured(): Record<string, unknown> {
    return {
      code: this.code,
      technicalMessage: this.message,
      userMessage: this.userMessage,
      recoverable: this.recoverable,
      allowedRecoveries: this.allowedRecoveries,
    };
  }
}

export type Query = Record<string, string | number | boolean | undefined>;

/**
 * Per-request budget for calls to weka-api.
 *
 * MUST sit below the Azure Container Apps ingress (Envoy) request timeout — a fixed,
 * non-configurable ~240s that answers with a bare HTTP 504. We reach weka-api through its
 * *internal ingress FQDN* (WEKA_API_URL), so this hop crosses the environment's edge proxy
 * and is subject to that ceiling just like the caller's hop into us.
 *
 * Aborting at 210s means the failure is OURS: it surfaces as a structured WekaApiError
 * carrying a `code` and recovery metadata, which the tool wrapper serialises for the
 * Recovery agent — instead of an opaque gateway 504 that arrives with no code at all and
 * leaves the agent nothing to act on. Override with WEKA_API_TIMEOUT_MS.
 */
const REQUEST_TIMEOUT_MS = Number(process.env.WEKA_API_TIMEOUT_MS ?? 210_000);

/** Thin typed HTTP client over the Weka REST API. */
export class WekaClient {
  constructor(private readonly baseUrl: string) {}

  get base(): string {
    return this.baseUrl;
  }

  /**
   * `fetch` with the request budget applied, turning a timeout into a typed WekaApiError.
   *
   * Every request in this client goes through here — a bare `fetch` would wait forever
   * (undici has no default request timeout) and let the gateway decide our failure mode.
   */
  private async fetch(url: string, init?: RequestInit): Promise<Response> {
    try {
      return await fetch(url, { ...init, signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) });
    } catch (e) {
      const name = e instanceof Error ? e.name : "";
      if (name === "TimeoutError" || name === "AbortError") {
        throw new WekaApiError(
          504,
          "WEKA_TIMEOUT",
          `weka-api did not respond within ${REQUEST_TIMEOUT_MS / 1000}s`,
          "This model took too long to train or evaluate on this dataset.",
          true,
          // Must come from the Recovery agent's closed vocabulary (retry, exclude_column,
          // change_transformation, ask_user, stop) — it is told never to invent an action
          // outside it, so an unknown value here would be worse than none.
          ["retry", "stop"],
        );
      }
      throw e;
    }
  }

  private url(path: string, query?: Query): string {
    const u = new URL(this.baseUrl + path);
    if (query) {
      for (const [k, v] of Object.entries(query)) {
        if (v !== undefined) u.searchParams.set(k, String(v));
      }
    }
    return u.toString();
  }

  /** Turn a non-2xx response into a WekaApiError, parsing the enriched body when present. */
  private async fail(res: Response): Promise<never> {
    let code = "HTTP_ERROR";
    let message = res.statusText;
    let userMessage: string | undefined;
    let recoverable: boolean | undefined;
    let allowedRecoveries: string[] | undefined;
    try {
      const body = await res.json();
      if (body && typeof body === "object") {
        const b = body as any;
        code = b.code ?? code;
        // Prefer the technical message; `error` is the legacy alias of the same.
        message = b.technicalMessage ?? b.error ?? message;
        userMessage = b.userMessage;
        recoverable = b.recoverable;
        allowedRecoveries = b.allowedRecoveries;
      }
    } catch {
      try {
        message = (await res.text()) || message;
      } catch {
        /* ignore */
      }
    }
    throw new WekaApiError(res.status, code, message, userMessage, recoverable, allowedRecoveries);
  }

  async get(path: string, query?: Query): Promise<unknown> {
    const res = await this.fetch(this.url(path, query));
    if (!res.ok) return this.fail(res);
    return res.json();
  }

  async post(path: string, body?: unknown, query?: Query): Promise<unknown> {
    const res = await this.fetch(this.url(path, query), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!res.ok) return this.fail(res);
    // Some endpoints return 204 No Content or plain text.
    if (res.status === 204) return { ok: true };
    const ctype = res.headers.get("content-type") ?? "";
    return ctype.includes("application/json") ? res.json() : res.text();
  }

  async del(path: string): Promise<unknown> {
    const res = await this.fetch(this.url(path), { method: "DELETE" });
    if (!res.ok) return this.fail(res);
    return { ok: true, status: res.status };
  }

  /** Upload a dataset file (ARFF/CSV) via multipart, mirroring `POST /datasets`. */
  async uploadDataset(name: string | undefined, filePath: string): Promise<unknown> {
    const bytes = await readFile(filePath);
    const form = new FormData();
    if (name) form.set("name", name);
    form.set("file", new Blob([bytes]), basename(filePath));
    const res = await this.fetch(this.url("/datasets"), { method: "POST", body: form });
    if (!res.ok) return this.fail(res);
    return res.json();
  }

  /** Import a serialized .model file under `name`, supplying `dataset` for the header. */
  async importModel(name: string, filePath: string, dataset: string): Promise<unknown> {
    const bytes = await readFile(filePath);
    const form = new FormData();
    form.set("dataset", dataset);
    form.set("file", new Blob([bytes]), basename(filePath));
    const res = await this.fetch(this.url(`/models/${encodeURIComponent(name)}/import`), {
      method: "POST",
      body: form,
    });
    if (!res.ok) return this.fail(res);
    return res.json();
  }

  /** Download a model's serialized bytes to `outPath`. Returns the byte count written. */
  async downloadModel(name: string, outPath: string): Promise<number> {
    const res = await this.fetch(this.url(`/models/${encodeURIComponent(name)}/download`));
    if (!res.ok) return this.fail(res);
    const buf = Buffer.from(await res.arrayBuffer());
    await writeFile(outPath, buf);
    return buf.length;
  }
}
