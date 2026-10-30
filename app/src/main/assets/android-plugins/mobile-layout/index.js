'use strict'

/**
 * dsh-mobile-layout 宿主端入口。
 *
 * 本插件的全部工作都在浏览器端（lib/client.js）完成：形态判定、抽屉、常驻
 * 操作条与浮层都走官方槽位和官方布局服务。宿主端因此只保留一个最小插件
 * 外壳 —— 它必须存在，cordis roster 才能把包挂到 profile 上，客户端模块
 * 扫描器也才能从同一个 package.json 发现 `./client` 入口。
 *
 * 约定：inject 为空数组（不依赖任何宿主服务），apply 不注册路由、不写文件、
 * 不抛错；卸载时仅返回空 disposer。
 */

function apply() {
  return function dispose() {}
}

module.exports = {
  name: 'mobile-layout',
  inject: [],
  apply,
}
