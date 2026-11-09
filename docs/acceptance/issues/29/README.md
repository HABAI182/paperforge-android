# #29 · 文档库按文件夹/分组呈现（轻量版）

本页记录本轮最小范围：**只利用已有的 `documents.folder` 字段**，在文档库顶部加一行横向可滑动的文件夹卡片；点卡片把下方列表过滤为该组，再点一次或点「全部」取消。不做文件夹的新建/重命名/移动/删除，不改导航栈，不引入依赖，不新增数据库迁移，不做大重构。

## 结论

- 分组依据是**真实字段**：`DocumentRow.folder`（`app/src/main/java/cn/paperforge/app/data/Database.kt:16`）。该列在 `Migration1To2` 就已存在（`Database.kt:412`），当前数据库版本 11，因此**没有新增迁移**。
- UI 入口在 **文档页签 → 全部** 的顶部、`最近 / 全部 / 收藏` 筛选条下方、「全部文档」标题上方，新增「文件夹」横向卡片行；与原有筛选条并存。
- JVM 单测 **8/8 通过**；设备测试 `FolderViewFlowTest` 在 `emulator-5554` **1/1 通过**，点「验收29-票据」卡片后列表只剩该组 3 份，组外 2 份不出现，二次点卡片与点「全部」都能清除过滤。
- 截图与 JSON 已从设备拉回：`work/folder-view/`，副本在本目录。

## 分组依据

### 真实字段，无迁移

| 项 | 实际情况 |
|---|---|
| 字段 | `documents.folder TEXT NOT NULL DEFAULT ''` |
| 来源 | `DocumentRow.folder`（`Database.kt:16`），`Migration1To2` 添加（`Database.kt:412`） |
| 当前 DB 版本 | 11（`Database.kt:306`） |
| 本轮迁移 | **无**；没有新增列、表、索引或 schema 版本 |
| 既有用途 | 「文件夹与标签」菜单、`FolderField`、高级筛选、自动导出规则、备份都已使用该字段；本轮只增加展示入口 |

### 规则

纯函数在 `app/src/main/java/cn/paperforge/app/data/LibraryGroups.kt`：

1. 用现有 `libraryFolder()` 归一化：反斜杠转 `/`、逐段 trim、去掉空段与首尾 `/`。所以 `工作 / 票据 `、`工作/票据/`、`工作\票据` 会合并为同一组。
2. 每个出现过的非空 folder 路径生成一张卡片；`folder` 为空/只有空白的文档归入「未分类」，且「未分类」永远排在最后。
3. 卡片计数使用**该文件夹及下级文件夹**的文档数，与既有 folder 筛选（`folder == path || folder.startsWith("$path/")`）完全一致；点父卡片看到的就是父卡片写的数量。
4. 卡片顺序：数量降序，数量相同按路径升序，保证重组/刷新时稳定。
5. 每个组内文档按 `modified` 降序、`id` 升序排列；`coverDocumentId` 取组内最新的一份。UI 目前只用了名称/数量，没有真的渲染封面（封面是可选项，本轮未启用）。

> 这不是“真正的文件夹管理”：没有创建/重命名/移动/删除，也没有可展开的树；卡片只是现有 `folder` 字符串的平铺入口。

## UI 入口与交互

- 位置：`HomeScreen` 的「文档 → 全部」页签，`最近/全部/收藏` 筛选条下方、「全部文档」标题上方（`HomeScreen.kt` 的 `folder-groups` item）。
- 组件：`app/src/main/java/cn/paperforge/app/ui/DocumentGroups.kt`，`LazyRow` + 152dp 卡片；卡片包含文件夹图标、名称、`N 份`，选中态用 `primaryContainer` 背景和主色描边。
- 点卡片：`groupKey = 该路径`，并把高级 `folder` 筛选清空，避免两个文件夹条件互相叠加。
- 再点同一张卡片：`groupKey = null`，恢复全量。
- 点卡片行里的「全部」：`groupKey = null`，同时清空高级 `folder` 筛选。
- 顶部原有的 `最近 / 全部 / 收藏` 芯片保留，点「全部」「收藏」也会清掉分组筛选；「最近」页签不显示分组行，分组筛选也不会偷偷作用于最近列表。
- 分组行只在「全部」页签、非回收站、非多选、文档非空时显示；回收站、最近、多选状态保持原样。
- 高级筛选面板里的 `FolderField`、已保存筛选、重置筛选都会清空 `groupKey`，避免旧分组过滤残留。
- 标签、收藏、`collection`（未识字/待导出等）仍可与分组叠加。此时列表是交集，但卡片上的数字仍是该文件夹的总数，不是叠加后的数量。

## 代码改动

| 文件 | 改动 |
|---|---|
| `app/src/main/java/cn/paperforge/app/data/LibraryGroups.kt` | 新增 `DocumentGroup` 与纯函数 `documentGroups()`：归一化、构建、计数、排序、未分类、组内顺序/封面。无 Android 依赖。 |
| `app/src/main/java/cn/paperforge/app/ui/DocumentGroups.kt` | 新增 `FolderGroupRow` 与卡片；横向 `LazyRow`，testTag、contentDescription、选中态。 |
| `app/src/main/java/cn/paperforge/app/ui/HomeScreen.kt` | 新增 `groupKey` 状态和 `inGroup()` 过滤；在「全部」页签插入分组行；顶部芯片、已保存筛选、重置、`FolderField`、回收站入口在相应位置清空 `groupKey`；给集合标题加 `collection-header` testTag 便于验证。diff：**+55 / -21**（含把标题包一层 `Box`）。 |
| `app/src/test/java/cn/paperforge/app/data/LibraryGroupsTest.kt` | 新增 8 个 JVM 用例。 |
| `app/src/androidTest/java/cn/paperforge/app/FolderViewFlowTest.kt` | 新增设备验证：真实 DB 写 folder → 点卡片 → 断言过滤 → 截图 → 二次点卡片/「全部」清除 → 写 JSON；结束时清理测试文档。 |

没有新增依赖；没有改 `#21/#23/#24/#25/#26/#27` 的业务代码或既有测试断言；没有改导航栈。

## 测试结果

### JVM 单测

`LibraryGroupsTest`：**8 tests / 0 failures / 0 errors**，0.049 s（`app/build/test-results/testDebugUnitTest`；副本 `unit-LibraryGroupsTest.xml`）。

| 用例 | 断言 |
|---|---|
| `emptyLibraryHasNoGroups` | 空列表返回空分组 |
| `singleFolderProducesOneGroupWithExactCount` | 单组路径、名称 `票据`、计数 2、组内集合正确 |
| `multipleFoldersAreSortedByCountThenPath` | 丙(3)、甲(2)、乙(1) 的顺序 |
| `unfiledGroupIsLastAndCountedSeparately` | 空白/空格 folder 归「未分类」，排最后，计数 2 |
| `subfolderDocumentsCountTowardTheParentCard` | 父卡片 4、子卡片 2，含更深层 `项目/票据/2026` |
| `blankSegmentsAndTrailingSlashesMergeIntoOneGroup` | 空格、尾斜杠、反斜杠归一为同一组，计数 3 |
| `membersAreOrderedByModifiedAndCoverIsTheNewestDocument` | `documentIds` 按 modified 降序，cover 取最新 |
| `libraryWithOnlyUnfiledDocumentsHasASingleUnfiledCard` | 全空 folder 时只有一张「未分类」卡，计数 3 |

### 设备测试（`emulator-5554`）

- 设备：`paperforge_api35(AVD)`，Android 15 / SDK 35，x86_64；沿用已有模拟器，未新开；屏幕截图 1080×2340。
- 测试写入 5 份文档（真实 `DocumentRow`，直接写 `documents.folder`）：
  - `验收29-票据-<stamp>`：3 份（甲/乙/丙）
  - `验收29-合同-<stamp>`：1 份
  - `""`（未分类）：1 份
- 测试从真实首页进入「全部」，横向滚动到票据卡片并点击：

| 检查 | 实测 |
|---|---|
| 卡片标签 | `验收29-票据-<stamp>，3 份文档` |
| 点击后集合标题 | `全部文档` + `3 份` |
| 组内标题 | 甲、乙、丙三份都能滚动到并显示 |
| 组外标题 | 合同、未分类都不能出现（`assertDoesNotExist`） |
| 卡片计数 vs 过滤结果 | 3 == 3，`countMatchesCard = true` |
| 再点同一卡片 | 列表恢复，合同文档可滚动到并显示，`secondClickCleared = true` |
| 点「全部」卡片 | 未分类文档可滚动到并显示，`allCardCleared = true` |
| 测试清理 | 结束后测试文档已删除；设备侧按 WAL 合并复核，无 `验收票据/验收合同/验收未分类` 残留 |

设备 JSON（`folder-view.json`）记录了同样的数字；截图（`folder-filter.png`）显示顶部「文件夹」卡片行、选中的「验收29-票...」卡片、`全部文档 / 3 份`，以及过滤后的两份票据文档（第三份在下一行，需要滚动）。

### 既有测试

`HomeUiTest`（3 个用例）本轮实测 **0/3 通过**，但失败点都在 HEAD 已存在的旧断言/旧行为上，不是本次改动引入：

| 用例 | 失败点 | 说明 |
|---|---|---|
| `homeToolsAndPrivacyAreUsable` | 第 20 行等 `onNodeWithText("纸匠")` | 当前主源码与 HEAD 都没有「纸匠」文案，首页标题是 `PaperForge`；测试未同步。 |
| `recentDocumentOffersTrashAndDirectDelete` | 第 39 行 `onAllNodesWithContentDescription("文档操作")` | 当前卡片更多按钮的 contentDescription 是 `"${doc.title}的更多操作"`；测试未同步。 |
| `pdfRendersAndCanScrollAndJump` | 第 74 行等待 PDF 标题出现在最近列表 | `registerPdf` 新建文档 `lastOpened = 0`，而 `HomeScreen` 的最近列表本来就要求 `lastOpened > 0`；这段逻辑本轮未改。 |

按规矩没有改这些既有断言。相关已知失败（`LibraryNamingTest` 的 BackupStore 孤儿缓存、`ExportPresetsFlowTest` 的 `images.json` 断言、`EditorExperienceTest` 的 AVD touch-injection）本轮没有重复运行，沿用交接记录，不计入 #29 回归。

## 已知限制

- **不是真正的文件夹**：没有新建/重命名/移动/删除文件夹，没有拖拽、没有树形展开；卡片是按现有 `folder` 字符串平铺的快捷筛选。
- **无迁移**：没有新增 `folder`/分类字段（字段本来就有），也没有迁移；本轮不写 `folder`，只读分组。
- **分组筛选是会话状态**：`groupKey` 用 `rememberSaveable`（配置变更时预期保留，本轮未单独实测旋转），但没有写入 `LibraryPreferences`，重新启动应用后恢复为不过滤；高级 folder 筛选与已保存筛选仍按原有方式持久化。
- **计数含下级**：父卡片的数量包含子文件夹，和既有 folder 筛选一致；如果用户把父卡片理解成“只算直接放在该层的文档”，会看到更大数字。没有单独的“精确层”计数。
- **未做封面缩略图**：卡片只用文件夹图标；`coverDocumentId` 已算出但 UI 未使用，避免一次渲染多张首页缩略图。
- **长路径会截断**：卡片宽 152dp，测试用的 `验收29-票据-<时间戳>` 在截图里显示为「验收29-票...」；真实短文件夹名（如「工作」）不受影响。
- **只出现在「全部」页签**：最近、收藏、回收站、多选状态不显示分组行；这时 `groupKey` 不参与过滤。
- **可与其他筛选叠加**：标签/收藏/collection 仍会继续过滤，列表是交集，但卡片数字不随这些条件变化。
- **空库不显示**：文档数为 0 时不显示「文件夹」行。
- **UI 走查有限**：只看了深色主题、1080×2340、默认字体；没有走查浅色/AMOLED、大字体、横屏、TalkBack。
- **真机未测**：只在 x86_64 API 35 模拟器验证，厂商 ROM 与真实触摸/滚动差异未验证。
- **设备 JSON 的 device 字段**：AVD 返回 `unknown Android SDK built for x86_64`，不是真实机型名；这是模拟器的 Build 属性。

## 未验证项

- 真实用户的多级文件夹（例如 `工作/2026/票据` 三层以上）、中文/空格/斜杠/超长路径在卡片上的可读性与点击命中。
- 文件夹数量很多（几十张卡片）时的横向滚动性能、首屏可发现性，以及是否需要“管理文件夹”入口。
- 分组卡片与高级 `FolderField`、已保存筛选、自动导出规则同时使用时的边界情况；代码上选择卡片会清空高级 folder 筛选，但没有逐项人工走查。
- 进程死亡/冷启动后分组筛选的恢复（预期冷启动后不保留，因为没写入偏好；未实测）。
- 浅色/AMOLED 主题、大字体、横屏、TalkBack 下卡片名称/数量/选中态的显示。
- 与 #21/#23/#24/#25/#26/#27 功能的组合场景；本轮没有改它们的逻辑，也没有做联合回归。
- 真机上的文件夹分组与触摸滚动；本轮只有模拟器证据。
- 空文件夹（没有任何文档的 folder 值）不会生成卡片；是否需要展示空组未实现、未验证。

## 证据文件

- 本目录：`README.md`、`folder-filter.png`（1080×2340，160,082 字节）、`folder-view.json`、`device-tests.xml`、`unit-LibraryGroupsTest.xml`、`home-ui-test.xml`。
- 设备私有目录：`/data/user/0/cn.paperforge.app/files/folder-view/`（`folder-filter.png`、`folder-view.json`）。
- 仓库外工作副本：`E:\CodexWorkspaces\2026-10-23\new-chat\work\folder-view\`（含截图、JSON、测试日志与结果 XML）。
