/**
 * Explicit container facts needed after subprocess drops ambient DSH_* values.
 * Keep this allowlist narrow: bridge credentials and privilege mode have their
 * own contributors; unrelated engine identity and secrets stay scrubbed.
 */
const name = "android-container-env";
const inject = ["shellEnv"];
const variables = {
  DSH_ANDROID_CONTAINER: { description: "Whether this engine runs in the Android Linux container." },
  DSH_NODE: { description: "Guest Node executable used by Android device gate scripts." },
  DSH_DNS_PORT: { description: "Local Android DNS proxy port used by the Linux loader." },
  DSH_TERMINAL_RUNNER: { description: "Host loader executable for reentering the Linux terminal." },
  DSH_TERMINAL_ROOTFS: { description: "Host rootfs path for Linux terminal reentry." },
  DSH_TERMINAL_INTERP: { description: "Guest dynamic linker for Linux terminal reentry." },
  DSH_TERMINAL_BINDS: { description: "Newline separated guest:host binds for Linux terminal reentry." },
};

function apply(ctx) {
  ctx.shellEnv.register({
    name,
    variables,
    resolve: () => Object.fromEntries(
      Object.keys(variables)
        .filter((key) => process.env[key] !== undefined)
        .map((key) => [key, process.env[key]]),
    ),
  });
}

export { apply, inject, name };
