// 读图链路端到端探针（issue #7 回归自测，设备端运行）
//
// 为什么需要：DSH 的图片上传/读取全部经过 @deepseek-ai/dsh-attachment-local。
// 该包在 Android 上有两个适配点（祖先目录 sync 撞只读根/沙箱外目录、SELinux 禁
// 硬链接），补丁错了或漏了都会让「存图即失败」，而 WebUI 上只表现为读不了图片。
// 本探针绕过 UI 直连该包，把链路压缩成一次 SAVE_OK/READ_OK 判定，便于真机回归。
//
// 用法（设备上，run-as 到 app 私有目录后）：
//   cd /data/data/app.dsh.mobile/files/engine
//   LD_LIBRARY_PATH=$PWD/lib ./bin/node <此文件> /data/data/app.dsh.mobile/files/dsh-home
//
// 期望输出：SAVE_OK ... 然后 READ_OK bytes=350（与输入同字节数）。
// 失败时打印 error.code —— EACCES/EINVAL 指向 syncDirectory 补丁缺失，
// ENOENT/EEXIST 指向 link shim 缺失。
import { readFileSync } from "node:fs";

const HOME = process.argv[2];
if (!HOME) {
  console.error("usage: node attachment-read-probe.mjs <DSH_HOME>");
  process.exit(2);
}

const MOD = HOME.replace(/\/dsh-home$/, "") + "/engine/lib/node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js";
const m = await import("file://" + MOD);

const limits = {
  maxImageBytes: m.DEFAULT_MAX_IMAGE_BYTES,
  maxImageDimension: m.DEFAULT_MAX_IMAGE_DIMENSION,
  maxImagePixels: m.DEFAULT_MAX_IMAGE_PIXELS,
  maxDimension: m.DEFAULT_NORMALIZED_IMAGE_MAX_DIMENSION,
  maxPixels: m.DEFAULT_NORMALIZED_IMAGE_MAX_PIXELS,
};
const policy = {
  maxBytes: m.DEFAULT_NORMALIZED_IMAGE_MAX_BYTES,
  maxDimension: m.DEFAULT_NORMALIZED_IMAGE_MAX_DIMENSION,
  maxPixels: m.DEFAULT_NORMALIZED_IMAGE_MAX_PIXELS,
};

// 16x16 RGBA PNG（350 字节），内嵌以免探针依赖外部文件
const PNG_B64 = "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAABJUlEQVR42mNgYOcTlVHWMrSwd/MNiU7KLCiva+2ZPGvhivXb9h45fenmg+fvvv5h5hKUkFfTNbF28gwIj0vNKa5q7OifNnfJ6k07GQ4cP3f1zuNXH3/8Z+MVkVbSNDC3c/UJjkrMyC+rbemeNHPB8nVb9xw+dfHG/Wdvv/xm4hQQl1PVMbZy9PAPi03JLqpkaGjvmzpn8aqNO/YfO3vl9qOXH77/Y+URllLU0DezdfEOikxIzyutae6aOGP+srVbdh86eeH6vadvPv9i5OAXk1XRNrJkcHD3C41JziqsqG/rnTJ70coN2/cdPXP51sMX77/9ZeEWklRQ1zO1cfYKjIhPyy2pbuqcMH3e0jWbdx08cf7a3SevP/1kGA2D0TAYDQNwGAAAGRD+EG1esjoAAAAASUVORK5CYII=";
const data = new Uint8Array(Buffer.from(PNG_B64, "base64"));
const root = HOME + "/attachments/v1";
console.log("root       =", root);
console.log("input bytes=", data.byteLength);

try {
  const t0 = Date.now();
  const ref = await m.saveImageFile(root, { data, mediaType: "image/png", name: "probe.png" }, limits, policy);
  console.log("SAVE_OK  ", Date.now() - t0, "ms", JSON.stringify(ref));
  const got = await m.readImageFile(root, ref);
  const bytes = got?.data?.byteLength ?? got?.bytes ?? null;
  console.log("READ_OK  bytes=", bytes);
  if (bytes !== data.byteLength) {
    console.log("FAIL 字节数不一致（应为", data.byteLength, "）");
    process.exit(1);
  }
} catch (e) {
  console.log("FAIL code=", e?.code, "msg=", e?.message);
  console.log((e?.stack || "").split("\n").slice(0, 8).join("\n"));
  process.exit(1);
}
