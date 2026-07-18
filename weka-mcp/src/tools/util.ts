import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { WekaApiError } from "../client.js";

/** Wrap a successful JSON payload as MCP text content. */
export function ok(payload: unknown): CallToolResult {
  const text = typeof payload === "string" ? payload : JSON.stringify(payload, null, 2);
  return { content: [{ type: "text", text }] };
}

/**
 * Wrap a thrown error as an MCP error result. A {@link WekaApiError} is emitted as
 * the structured enriched shape (master §10.5) as a JSON string, so the FastAPI
 * client can recover `code`/`userMessage`/`recoverable`/`allowedRecoveries` and
 * the Recovery agent can branch on them. Any other error degrades to its string
 * form (unchanged behaviour).
 */
export function fail(err: unknown): CallToolResult {
  const text =
    err instanceof WekaApiError
      ? JSON.stringify(err.toStructured())
      : String(err);
  return { content: [{ type: "text", text }], isError: true };
}

/** Run an async producer, mapping success/failure to MCP content. */
export async function run(fn: () => Promise<unknown>): Promise<CallToolResult> {
  try {
    return ok(await fn());
  } catch (e) {
    return fail(e);
  }
}
