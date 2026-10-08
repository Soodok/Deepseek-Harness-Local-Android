/**
 * [dsh-android] Managed shell variable: DSH_BRIDGE_TOKEN.
 *
 * Why this plugin exists: the app mints a per-process token and exports it as
 * DSH_BRIDGE_TOKEN into the engine's environment. The agent's shell tools then
 * call the local bridges (scr / notify / say / shz -> 127.0.0.1:3083/3082) and
 * those bridges now require an `X-DSH-Token` header. Without this plugin the
 * token never reaches the agent's shell: dsh-subprocess rebuilds every child
 * environment via `scrubbedParentEnv()`, which drops anything starting with
 * `DSH_` (see @deepseek-ai/dsh-subprocess: `!key.toUpperCase().startsWith("DSH_")`),
 * and dsh-shell-env then injects only what was registered through
 * `shellEnv.register()`.
 *
 * Consequence if this file is missing: every gate script gets an empty
 * DSH_BRIDGE_TOKEN, sends no header, and the bridge answers 403 - the agent
 * would be locked out of the device capabilities it is supposed to have.
 *
 * Same mechanism as android-priv-mode.mjs (and dsh-web-app's DSH_WEB_URL).
 * Loaded from the app's overlay patch by absolute path.
 *
 * @module dsh-android-bridge-token
 */

/** Environment variable the app exports into the engine process. */
const TOKEN_ENV = "DSH_BRIDGE_TOKEN";

/** Managed variable name the agent's shell reads (keeps the DSH_ prefix). */
const TOKEN_KEY = "DSH_BRIDGE_TOKEN";

/** Cordis plugin name. */
const name = "android-bridge-token";

/** Service this plugin needs before it can register its contribution. */
const inject = ["shellEnv"];

/**
 * Provide the managed variable.
 * @param ctx - plugin context whose `shellEnv` registry receives the contribution.
 */
function apply(ctx) {
  ctx.shellEnv.register({
    name,
    variables: {
      [TOKEN_KEY]: {
        description:
          "Per-process token for the Android bridge on 127.0.0.1:3083 (device " +
          "capabilities: screen reading, tapping, notifications, TTS). The gate " +
          "scripts (scr / notify / say) send it automatically; only read this " +
          "directly if you talk to the bridge yourself.",
      },
    },
    // Resolve per shell call: the token is stable for the app process lifetime,
    // but resolving keeps the registry from caching a value across engine
    // restarts. Empty string when the app did not export it (engine started
    // outside the app) - the bridge then fails closed, which is intended.
    resolve: () => ({ [TOKEN_KEY]: process.env[TOKEN_ENV] || "" }),
  });
}

export { apply, inject, name };
