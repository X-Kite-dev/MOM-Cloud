# WMS 固定范围升级包

本目录只构建和验证发布包，不连接运行库。原 SQL 仍是唯一升级真源；构建器冻结 7 份独立脚本及累计 V4 的 356、359 两节，不执行其他累计章节。

当前交付及操作说明：`outputs/20261031_wms_release_closure/README.md`（相对 MOM 工作区根目录）。

## 构建与验证

从 MOM 工作区根目录运行：

```sh
python3 -m unittest discover -s Code/Mom-Cloud/mom-wms/database/release -p test_release_builder.py -v
python3 Code/Mom-Cloud/mom-wms/database/release/build_release.py --output outputs/20261031_wms_release_closure/package
```

正式构建要求 `schema-contract.json` 标记为已验证，来源哈希与当前 9 个单元、构建/演练程序完全一致。来源或程序变化后必须重新做隔离演练，禁止手改契约或哈希绕过。来源哈希使用 UTF-8 解码、换行归一后的文本。`--draft` 产物仅用于全新临时 PG 建立契约，不能拿到测试库执行。

发布目录不可变：相同内容只核对；不同内容或不完整旧目录必须选择全新输出路径。新包先在同文件系统暂存完整目录，再一次改名发布，失败不会逐文件覆盖旧包。

完整复演：

```sh
python3 Code/Mom-Cloud/mom-wms/database/release/verification/run_isolated.py \
  --output outputs/20261031_wms_release_closure \
  --runtime-metadata outputs/20261031_wms_production_source_links/pg-runtime.json
```

复演复用已校验的本机 PG16.15 二进制、Java21 和本机 JDBC 驱动；创建全新数据目录，只监听 127.0.0.1，连接后核对真实 `data_directory`。候选包全部演练通过且临时实例停止后，才生成 READY 包并原子替换长期契约；失败保留原正式包/契约和本次 attempt 失败证据。`baseline.sql` 是最小合成前置结构，不是现场建表脚本，也不是云库备份。

## 发布边界

产出 01 只读预检、02 单一事务升级、03 只读复核和 `manifest.json`。新增对象与 330 作废审计前置规则按隔离 PG 捕获的结构契约核对，未知同名对象停止处理。字段/函数/索引/触发器或来源变化需要重新核查，不能简单改成 `CREATE IF NOT EXISTS` 放行。

本机 PG16 演练不证明目标 PG18.3 的实际历史、并发写入口、全部旧触发器或业务数据兼容。目标库预检、可恢复备份、发布窗口以及升级后运行联验另行完成；不把包生成视为功能已在运行环境可用。
