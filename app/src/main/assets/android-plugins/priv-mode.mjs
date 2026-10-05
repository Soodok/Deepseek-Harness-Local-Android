/**
 * [dsh-android] Managed shell variable: DSH_ANDROID_PRIV_MODE.
 *
 * Why this plugin exists: the app exports DSH_ANDROID_PRIV_MODE into the engine
 * process environment, but that value never reaches the agent's shell. dsh's
 * shell-env registry rebuilds the DSH_* namespace for every shell call and
 * injects only its own snapshot — ambient DSH_* values inherited from the
 * process are deliberately discarded. So a variable the agent is documented to
 * read must be registered through `shellEnv.register()`, exactly as
 * dsh-web-app does for DSH_WEB_URL.
 *
 * Loaded from the app's overlay patch by absolute path (the overlay's insert
 * entries turn filesystem paths into file:// URLs), so it needs no package
 * installation.
 *
 * @module dsh-android-priv-mode
 */

/** Environment variable the app exports into the engine process. */
const PRIV_MODE_ENV = "DSH_ANDROID_PRIV_MODE";

/** Managed variable name the agent reads (must keep the DSH_ prefix). */
const PRIV_MODE_KEY = "DSH_ANDROID_PRIV_MODE";

/** Cordis plugin name. */
const name = "android-priv-mode";

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
      [PRIV_MODE_KEY]: {
        description:
          "Android app privilege mode for this engine (normal | shizuku | root). " +
          "Determines whether `su` is usable and which capabilities the app grants.",
      },
    },
    // Resolve per shell call, so a value the app changes on restart is picked up
    // without the registry holding a stale copy. Falls back to "normal" when the
    // app did not export it (engine started outside the app), matching the app's
    // own default so the agent never reads an empty string.
    resolve: () => ({ [PRIV_MODE_KEY]: process.env[PRIV_MODE_ENV] || "normal" }),
  });
}

export { apply, inject, name };
