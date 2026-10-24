# directory-scoped-search Specification

## Purpose

让用户在已配置索引来源内选择一个目录，将名称、正文或目录名称检索限定到该目录及整棵子目录树；同时建立路径边界、来源校验、旧索引提示和桌面/Web 一致契约，避免将范围错误或执行失败解释为没有匹配。

## Requirements

### Requirement: DS-01 默认整树范围

系统 SHALL 对单个可选目录默认递归检索全部层级后代，无需逐个添加子目录，不提供递归开关。目录名称模式 SHALL 允许选定目录自身参与名称匹配，但不生成索引中不存在的目录结果。范围 SHALL 按路径段边界判断。

#### Scenario: 深层与前缀兄弟
- **WHEN** 选 E:\Docs\ProjectA，同一关键词命中 ProjectA\a.txt、ProjectA\sub\deep\b.txt、ProjectAB\c.txt、ProjectB\d.txt
- **THEN** 只有前两项进入该范围候选

#### Scenario: 根自身与目录名称
- **WHEN** 选 ProjectA，以目录名称模式查询能匹配根和后代的词，索引存在相应目录记录
- **THEN** 根与后代目录可返回，兄弟目录不可返回，文件正文命中不自动生成父目录结果

### Requirement: DS-02 平台路径表达

系统 SHALL 按服务器宿主平台解释绝对目录，去前后空白、词法消除 . 与 ..；Windows 统一大小写及支持的分隔符，POSIX 保留大小写。空值/空白表示无限定。系统 SHALL 支持 Windows 盘符根与 UNC 根，拒绝非空相对路径及平台非法语法，不把路径当关键词语法。Windows扩展/设备命名空间本期明确不支持，输入返回INVALID_DIRECTORY，不能静默变为其他路径。

#### Scenario: 等价表达与根
- **WHEN** Windows 输入 E:\Docs\ProjectA、e:/docs/projecta/、E:\Docs\Other\..\ProjectA，或选已配置 E:\、\\server\share\
- **THEN** 等价表达范围一致，不跨盘符/共享，根自身按 DS-01 参与目录匹配

#### Scenario: 特殊字符及大小写
- **WHEN** 合法路径含中文、日文、空格、&、#、+、括号、方括号
- **THEN** 按字面路径处理；POSIX /data/Docs 与 /data/docs 不合并

#### Scenario: 非法输入
- **WHEN** 关键词非空，目录为 Docs、Windows E:Docs 或含 NUL
- **THEN** 返回 INVALID_DIRECTORY，不回退全局或伪成功零结果

### Requirement: DS-03 条件组合与数量

系统 SHALL 将目录与关键词、类型、匹配模式、扩展名、修改时间取交集；目录/类型 SHALL 在数量限制前生效，目录不额外改变评分。旧请求 SHALL 保持成功结构、合法关键词和评分语义；允许纠正类型过滤过晚造成的少返回，并按DS-09统一错误语法为400。

#### Scenario: 条件组合
- **WHEN** 同目录分别使用 NAME、CONTENT、ALL、FOLDER、两种匹配模式及扩展名/时间
- **THEN** 每项同时满足所有条件；ALL 保持文件名称加正文含义，目录用 FOLDER

#### Scenario: 范围与类型不挤占名额
- **WHEN** limit=2，范围外高分项或被排除类型超过原抓取量，仍有两个合格文件
- **THEN** 返回两个合格文件，不因先截断再过滤而少返回

### Requirement: DS-04 来源覆盖与索引真实性

对非空关键词的限定请求，系统 SHALL 验证目录等于来源或位于来源子树，否则返回 DIRECTORY_NOT_INDEXED 并提示先加来源。配置文件不可读或全部数据行无效 SHALL 返回 SOURCE_CONFIG_UNAVAILABLE；单行无效 SHALL 记录行号/原因并跳过，剩余有效来源继续。空/缺失文件只表示无来源。查询 SHALL 不扫描、加来源、自动建库或用在线存在检查阻止离线索引查询。

#### Scenario: 来源内与更大父目录
- **WHEN** 来源仅 E:\Docs\ProjectA，分别限定该根、其子目录、E:\Docs、ProjectAB
- **THEN** 前两者允许，后两者 DIRECTORY_NOT_INDEXED，即使索引残留范围外记录也不放行

#### Scenario: 来源变更与错误
- **WHEN** 删除来源后发起下一限定请求，或来源配置不可读/全部数据行非法
- **THEN** 分别报告 DIRECTORY_NOT_INDEXED 或 SOURCE_CONFIG_UNAVAILABLE，不复用旧结论或吞成空配置

#### Scenario: 有效与坏行混合
- **WHEN** sources.txt有一个有效来源及无关相对/多余列坏行
- **THEN** 坏行记行号日志，合法来源可查；桌面刷新给一次非模态警告指向日志，修复后清除；HTTP不披露坏行详情

#### Scenario: 离线及无匹配
- **WHEN** 已配置 UNC 离线，或有效索引无匹配/已有commit且0个存活记录
- **THEN** 不做网络访问校验；有记录查索引，无记录返回零，界面提示仅查索引、尚未建索引可到桌面执行，不宣称目录为空、存在或完整覆盖

#### Scenario: 尚无索引提交
- **WHEN** 已配置来源但服务无法打开索引或完全没有有效索引提交
- **THEN** 返回INDEX_UNAVAILABLE/503，统一说明可到桌面File → Index All Sources建立索引或等待初始化/重建，搜索不会为此创建空索引

### Requirement: DS-05 旧与混合索引

系统 SHALL 在同一查询快照验证所有存活文档具备范围数据，任一缺失则限定请求 INDEX_REBUILD_REQUIRED；检查失败 SCOPE_CHECK_FAILED；刷新后重新判定。无目录请求 SHALL 不受此门槛影响。

#### Scenario: 旧、混合与刷新
- **WHEN** 全旧或混入一个不匹配关键词的存活旧记录，再经刷新变为全部新记录，之后又追加旧记录
- **THEN** 限定请求依次拒绝、恢复、再拒绝；普通请求可用

#### Scenario: 已删除、空与读错误
- **WHEN** 缺字段文档已删除、索引为空或检查发生读取错误
- **THEN** 前两种不要求重建，后者 SCOPE_CHECK_FAILED，不返回伪成功

#### Scenario: 非破坏迁移与陈旧记录
- **WHEN** 旧索引执行File → Index All Sources，分别没有陈旧记录，或留有已删除文件/已移除来源的缺字段存活记录
- **THEN** 前者补齐后可限定搜索；后者仍409，不静默漏旧记录，提示备份后手动File → Rebuild Index (Full)兜底；系统不自动触发破坏性操作

### Requirement: DS-06 全量增量一致

系统 SHALL 在实际全量和增量文件/目录写入中维护一致范围数据，保留原展示路径和更新标识，不因本功能新增重复记录。

#### Scenario: 全量再更新
- **WHEN** 扫描目录树再更新某文件，提交并刷新
- **THEN** 文件和目录按范围可查，返回原路径，同一主键不增加第二个存活记录

### Requirement: DS-07 桌面便利操作

桌面 SHALL 提供来源选择、路径输入、仅目录 Browse、Clear、当前范围/包含全部子目录说明。默认无限定，不新增路径历史持久化。确认操作 SHALL 提交一次；取消不变；Clear 保留词/类型/过滤并恢复原全局查询。

#### Scenario: 选择清除
- **WHEN** 选来源或输入/浏览确认子目录，再取消浏览，最后 Clear
- **THEN** 每次确认一次请求，取消不发，Clear 一次全局请求；持续显示实际提交范围

#### Scenario: 编辑值与来源刷新
- **WHEN** 手改路径、选All sources、关闭来源管理或完成Index All/Full Rebuild
- **THEN** 下拉复用查询规范按等价路径显示来源/Custom/All sources，非法编辑只Custom不自动查；All sources等同Clear且只提交一次；来源列表刷新不改路径或自动查询；移除来源后保留路径并在下次提交校验，不自动扩大范围

#### Scenario: 竞态与禁用
- **WHEN** A 后提交 B，A 成功/错误迟到；或清空词提交；或正在完整索引
- **THEN** 旧回调不覆盖 B/空词，worker 用快照，目录控件和动作遵守既有禁用

### Requirement: DS-08 Web 服务器目录

Web SHALL 接受可选 directory，展示服务器路径说明、Clear、当前整树范围和索引提示，仅支持手输服务器路径或从结果缩小，不提供来源列表、服务器浏览或来源修改接口；用户已确认422会间接泄露来源覆盖关系，不声称该规则提供访问控制。成功 SHALL 保持 count/results，count 是返回数，允许每项兼容增加由服务器计算的parentDirectory以供父目录快捷搜索。限定错误 SHALL 使用 design D3 的固定 code、HTTP 状态、安全消息。

#### Scenario: 编码兼容
- **WHEN** 目录含中文、空格、+、&、#、方括号，或旧客户端不传 directory
- **THEN** 后台收到完整原路径；JSON 合法；默认值及 limit 不变；非英语区域和控制字符不破坏 JSON

#### Scenario: 当前及迟到错误
- **WHEN** 当前请求失败，或 A 成功/失败在 B 后返回
- **THEN** 当前失败清空旧结果/预览并提示；旧 A 不覆盖 B；空词提交不发 HTTP 且失效旧响应

### Requirement: DS-09 错误与语法一致

系统 SHALL 区分INVALID_DIRECTORY、DIRECTORY_NOT_INDEXED、SOURCE_CONFIG_UNAVAILABLE、INDEX_REBUILD_REQUIRED、SCOPE_CHECK_FAILED、SEARCH_FAILED、INVALID_QUERY、INDEX_UNAVAILABLE。目录相关错误不回退全局；有/无目录的查询解析错误 SHALL 一致返回INVALID_QUERY/400，不能被当作零结果或服务故障。真正限定执行故障 SHALL 返回SEARCH_FAILED/500并释放资源。

#### Scenario: 相同非法查询
- **WHEN** 可用服务对report AND (分别发起有效目录内与无目录查询
- **THEN** 两者均400 INVALID_QUERY，界面提示修正语法，按用户输入错误记录而非服务端故障，失败不加入查询历史

#### Scenario: 桌面固定错误文案
- **WHEN** 当前worker收到八个已知Code或未知异常，异常含原始敏感消息
- **THEN** 仅显示固定可操作提示，未知映射SEARCH_FAILED，清旧结果/预览，不显示原始消息/堆栈，陈旧或取消回调不覆盖当前状态

#### Scenario: 真正执行失败
- **WHEN** 路径/来源/语法合法但限定查询发生I/O或运行时故障
- **THEN** SEARCH_FAILED，日志保留原因，不伪报零匹配或泄漏堆栈

### Requirement: DS-10 空词与兼容边界

系统 SHALL 对空词保持不搜索/空结果，不因目录输入而浏览、读来源或触发迁移。目录条件 SHALL 不改变打开/预览/分享权限和现有合法名称/正文匹配规则。

#### Scenario: 空词优先
- **WHEN** 空白关键词配非法目录、范围外目录或不可读来源
- **THEN** 桌面提示输入词，HTTP 返回原空成功结构，不执行来源/索引检查

### Requirement: DS-11 从结果继续定位

桌面/Web SHALL 支持目录“在此目录搜索”、文件“在所在目录搜索”，保留词/类型/过滤并提交一次；Web SHALL 支持复制完整结果路径并如实报告失败。快捷动作仍受来源校验。

#### Scenario: 排序与特殊路径
- **WHEN** 对排序后结果使用快捷搜索，路径含单引号等合法字符
- **THEN** 使用实际结果的自身/服务器语义父目录（Windows盘根、UNC共享根、POSIX根均正确），更新范围且只提交一次，不选错行或执行拼接脚本；根自身操作不循环

#### Scenario: 复制
- **WHEN** 浏览器允许或拒绝剪贴板
- **THEN** 分别复制完整路径或提供可选文本/明确失败，不伪报成功


### Requirement: DS-12 建库恢复与重建期间可用性

系统 SHALL 在Index All Sources产生有效提交后刷新已有服务，或在原服务不可用时后台重开并发布给桌面和Web；整体失败但有有效commit也尝试恢复，并保留部分成功/失败提示，无需重启或强制重建。系统 SHALL 在完整重建开始前让新的非空Web搜索返回INDEX_UNAVAILABLE/503，避免清空期伪报无匹配；成功恢复后可查询，失败明确不可用。打开索引 SHALL 不阻塞EDT，旧候选不能覆盖新服务/已关闭窗口。

#### Scenario: 全新安装首次建库
- **WHEN** 临时数据目录无commit、添加来源、执行File → Index All Sources后查询
- **THEN** 建库前是可操作的不可用提示，提交/服务打开后桌面及Web都能限定检索，不需要重启

#### Scenario: 重建和迟到打开
- **WHEN** 手动完整重建进行中、重建失败，或旧后台打开结果晚于新请求/窗口关闭
- **THEN** 新非空请求503，成功后恢复、失败不伪报成功，迟到候选释放且不能回写；空词仍返回原空成功结构

#### Scenario: worker排队期间服务不可用
- **WHEN** 请求已提交、token未失效，但worker执行前服务被置null
- **THEN** INDEX_UNAVAILABLE固定提示，不能伪报No results；真实重建还会使旧请求失效，不允许迟到覆盖重建状态

#### Scenario: Index All部分提交
- **WHEN** 第一来源已commit、第二来源失败，整体报错且原服务为null
- **THEN** 后台尝试打开可用commit、已提交结果可查但保留失败/部分完成提示；无commit仍INDEX_UNAVAILABLE，不伪报Ready或完整覆盖
