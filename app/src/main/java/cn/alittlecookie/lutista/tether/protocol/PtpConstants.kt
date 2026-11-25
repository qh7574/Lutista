package cn.alittlecookie.lutista.tether.protocol

/**
 * 联机模式 PTP 协议常量。
 *
 * ## 数值来源
 * 全部来自真机实测（Panasonic DC-S1 固件 2.4），并与两份**可核对**的材料交叉验证：
 * - **PIMA 15740:2000 全文**（离线副本 `.docs/tools/ptp/pima15740-2000.pdf`，159 页）：
 *   `Operation Code 表` / `ObjectFormatCode 表` / `EventCode 表` / `DevicePropCode 表` / `§11.3 响应码`。
 * - **真机 dump** `app/src/test/resources/fixtures/` 下的 `.hex`（仓库内，任何人可 `xxd` 复核）。
 *
 * ## 出处纪律（本项目规范）
 * 每个常量旁必须有一行可核对的坐标，**没有出处就默认不可信**：
 * 1. 有公开标准的 → 规范表名 + 规范符号名（PIMA 章节号仅在核对过原文时写）；
 * 2. 厂商私有码 → 标「真机实测」+ 定稿章节，并把语义写清（让读者不依赖文档也能用）；
 * 3. 有 dump 支撑的 → 补 fixture 文件名（仓库内可自证，优先级最高）。
 *
 * ⚠️ 本文件曾因「凭记忆」把 `0x2013` 当成 `SpecificationByFormatUnsupported`（那其实是 `0x2014`），
 * 定稿文档已记录该次误判。改任何数值/名称前请回查上面两份材料，**不要凭记忆**。
 *
 * 纯 Kotlin，零 Android 依赖，可 JVM 单测。
 */
object PtpConstants {

    /** 容器头固定 12 字节：`length u32` + `type u16` + `code u16` + `txid u32`，全小端。
     *  出处：PIMA 15740 容器头字段表（偏移 0/4/6/8）；定稿 §1.1 / §1.4 真机实测一致。 */
    const val CONTAINER_HEADER_SIZE: Int = 12

    /** 出处：**真机实测**（定稿 §1.4）。相机主动事件的 transaction id：**S1 实测恒为 `0xFFFFFFFF`**，不能用它做校验（定稿 §1.4）。
     *
     *  ⚠️ 规范差异（已核对 PIMA 15740 原文）：规范说事件容器的 TransactionID 字段
     *  「若事务号不适用则为 `0x00000000`」，而 S1 发的是 `0xFFFFFFFF`。
     *  两个值都可能出现 → **任何主机实现都不得用 txid 校验事件**。 */
    const val EVENT_TRANSACTION_ID: Long = 0xFFFF_FFFFL

    /** 出处：定稿 §4.1。通配对象 handle / storageId（枚举一律传它）。 */
    const val ALL_HANDLES: Long = 0xFFFF_FFFFL

    /** 出处：定稿 §4.1。[ALL_HANDLES] 的语义别名：`GetObjectHandles` 的 StorageID 参数。 */
    const val ALL_STORAGE_IDS: Long = 0xFFFF_FFFFL

    /** 出处：定稿 §4.1。`GetObjectHandles` 的 ObjectFormatCode 参数一律传 0。 */
    const val ANY_OBJECT_FORMAT: Long = 0L

    /** 出处：定稿 §4.1。`GetObjectHandles` 根层查询的 ParentObjectHandle。 */
    const val ROOT_PARENT_HANDLE: Long = 0xFFFF_FFFFL

    /** 出处：PIMA 15740 OpenSession 语义 + 真机实测（定稿 §1.1）。参数 = `[1]`。 */
    const val DEFAULT_SESSION_ID: Long = 1L

    /** 出处：真机实测（定稿 §2.3）。属性值占位：ISO = AUTO / 快门 = BULB（按属性语义区分）。 */
    const val VALUE_UNDEFINED: Long = 0xFFFF_FFFFL

    /** 出处：真机实测（定稿 §2.3 / §3.2）。属性值占位：无镜头数据（手动镜头），UI 显示 `—`。 */
    const val VALUE_NO_LENS_DATA: Long = 0xFFFEL

    // ────────────────────────────── 容器类型 ──────────────────────────────

    /**
     * 容器头 `type` 字段的取值。
     *
     * 出处：PIMA 15740 容器头字段表原文「Container Type：0 undefined / 1 Command Block /
     * 2 Data Block / 3 Response Block / 4 Event Block / otherwise reserved」（已核对离线 PDF）。
     */
    object ContainerType {
        /** 命令容器（Host → Camera）。 */
        const val COMMAND: Int = 1

        /** 数据容器（双向；code 为所属 opcode 的 echo）。 */
        const val DATA: Int = 2

        /** 响应容器（Camera → Host；code 为响应码）。 */
        const val RESPONSE: Int = 3

        /** 事件容器（Camera → Host，走中断端点）。 */
        const val EVENT: Int = 4
    }

    // ────────────────────────── 标准 opcode（用到的） ──────────────────────────

    /**
     * 标准 PTP opcode。只收录实际会用到的条目，不要补全整张表。
     *
     * 16 条的规范名已与 PIMA 15740 `Operation Code 表` 逐字比对（16/16 命中）；
     * 存在性还可用 `fixtures/device_info.hex` 的 `OperationsSupported` 直接复核。
     */
    object Op {
        /** 出处：PIMA 15740 Operation Code 表（GetDeviceInfo）；dump 的 OperationsSupported 含此码。 */
        const val GET_DEVICE_INFO: Int = 0x1001

        /** 出处：PIMA 15740 Operation Code 表（OpenSession）；参数 `[1]`。 */
        const val OPEN_SESSION: Int = 0x1002

        /** 出处：PIMA 15740 Operation Code 表（CloseSession）；无参数。 */
        const val CLOSE_SESSION: Int = 0x1003

        /** 出处：PIMA 15740 Operation Code 表（GetStorageIDs）；可作热身命令。 */
        const val GET_STORAGE_IDS: Int = 0x1004

        /** 出处：PIMA 15740 Operation Code 表（GetStorageInfo）；S1 对任何 ID 都返回 0x2008，不能当门禁。 */
        const val GET_STORAGE_INFO: Int = 0x1005

        /** 出处：PIMA 15740 Operation Code 表（GetNumObjects）。 */
        const val GET_NUM_OBJECTS: Int = 0x1006

        /** 出处：PIMA 15740 Operation Code 表（GetObjectHandles）；dump 的 OperationsSupported 含此码。
         *  ⚠️ **不能作为会话第一条命令**（定稿 §6 约束 1）。 */
        const val GET_OBJECT_HANDLES: Int = 0x1007

        /** 出处：PIMA 15740 Operation Code 表（GetObjectInfo）；S1 布局见定稿 §4.2（Parent@34 / Name@52）。 */
        const val GET_OBJECT_INFO: Int = 0x1008

        /** 出处：PIMA 15740 Operation Code 表（GetObject）；整包下载。 */
        const val GET_OBJECT: Int = 0x1009

        /** 出处：PIMA 15740 Operation Code 表（GetThumb）。 */
        const val GET_THUMB: Int = 0x100A

        /** 出处：PIMA 15740 Operation Code 表（DeleteObject）。 */
        const val DELETE_OBJECT: Int = 0x100B

        /** 出处：PIMA 15740 Operation Code 表（FormatStore）。 */
        const val FORMAT_STORE: Int = 0x100F

        /** 出处：PIMA 15740 Operation Code 表（GetDevicePropDesc）；S1 的能力探测走私有 0x9108。 */
        const val GET_DEVICE_PROP_DESC: Int = 0x1014

        /** 出处：PIMA 15740 Operation Code 表（GetDevicePropValue）。 */
        const val GET_DEVICE_PROP_VALUE: Int = 0x1015

        /** 出处：PIMA 15740 Operation Code 表（SetDevicePropValue）；需 DATA OUT 阶段。 */
        const val SET_DEVICE_PROP_VALUE: Int = 0x1016

        /** 出处：PIMA 15740 Operation Code 表（GetPartialObject）；RAW 内嵌 JPEG 预览用。 */
        const val GET_PARTIAL_OBJECT: Int = 0x101B
    }

    // ─────────────────────── Panasonic 私有 opcode（实测可用） ───────────────────────

    /**
     * 出处：**真机实测**（DC-S1 固件 2.4），见定稿 §1.3。
     *
     * 这些码在 `fixtures/device_info.hex` 的 `OperationsSupported` 里**一条都没有**，
     * 而全部实测可用 —— `OperationsSupported` 不能当可用性白名单（定稿 §1.2 / v8 修正 ①）。
     */
    object PanasonicOp {
        /** 出处：真机实测（定稿 §1.3）。语义：读属性当前值，参数 `[propCode]`。 */
        const val GET_PROP_VALUE: Int = 0x9402

        /** 出处：真机实测（定稿 §1.3）。语义：写属性，参数 `[propCode]` + DATA OUT。
         *  只能写「值属性」`0x..X1`，写容器属性 `0x..X0` 必 `0x201D`（定稿 §3.7）。 */
        const val SET_PROP_VALUE: Int = 0x9403

        /** 出处：真机实测（定稿 §1.3）。语义：读属性描述符（枚举表），参数 `[propCode, 0, 0]`。
         *  描述符 `count > 0` ⟺ 可调可写（定稿 §2.3）。 */
        const val GET_PROP_DESC: Int = 0x9108

        /** 出处：真机实测（定稿 §1.3 / §5.1）。语义：拍照 / 录像 toggle，参数 `[0x03000011]`。
         *  返回码恒 `0x2001` 且无 data，两种状态无法区分 → 状态机必须由 App 维护。 */
        const val CAPTURE: Int = 0x9404

        /** 出处：真机实测（定稿 §1.3 / §5.2）。语义：AF / AE 控制，参数 `[0x03000021~25]`。 */
        const val AF_AE_CONTROL: Int = 0x9405

        /** 出处：真机实测（定稿 §1.3 / §5.3）。语义：取景开关，参数 `[0x0D000010]` 开 / `[0x0D000011]` 关。 */
        const val LIVEVIEW_CONTROL: Int = 0x9412

        /** 出处：真机实测（定稿 §1.3 / v7 §10.1）；dump `fixtures/liveview_formats.hex`（36 B / 3 档）。
         *  语义：取景格式表，参数 `[0x0D800012]`。 */
        const val GET_LIVEVIEW_FORMATS: Int = 0x9414

        /** 出处：真机实测（定稿 §9，**参数含义未实测**）。语义：手动对焦驱动，参数 `[0~4]`。 */
        const val MANUAL_FOCUS_DRIVE: Int = 0x9416

        /** 出处：真机实测（定稿 §1.3 / §5.3）。语义：取一张取景帧，无参数；每帧一个 DATA 容器。 */
        const val GET_LIVEVIEW_FRAME: Int = 0x9706
    }

    // ────────────────────────────── 响应码 ──────────────────────────────

    /**
     * 实际会遇到的响应码。
     *
     * 每条都给：规范符号名 + 两个公开实现（libgphoto2 `ptp.h`、`android.mtp.MtpConstants`）。
     * PIMA 章节号已按 **`§11.3.n = 0x2001 + (n-1)`** 逐条算术自查 —— 这条算术能当场抓出「记错一格」。
     */
    object Rc {
        /** 出处：0x2001 = `PTP_RC_OK`；对照 `MtpConstants.RESPONSE_OK`；PIMA 15740 §11.3.1。 */
        const val OK: Int = 0x2001

        /** 出处：0x2002 = `PTP_RC_GeneralError`；对照 `MtpConstants.RESPONSE_GENERAL_ERROR`；PIMA 15740 §11.3.2。 */
        const val GENERAL_ERROR: Int = 0x2002

        /** 出处：0x2006 = `PTP_RC_ParameterNotSupported`；对照 `MtpConstants.RESPONSE_PARAMETER_NOT_SUPPORTED`；
         *  PIMA 15740 §11.3.6。S1 上「按具体 storage 精查」就是它（定稿 §4.1）。 */
        const val PARAMETER_NOT_SUPPORTED: Int = 0x2006

        /** 出处：0x2008 = `PTP_RC_InvalidStorageId`；对照 `MtpConstants.RESPONSE_INVALID_STORAGE_ID`；
         *  PIMA 15740 §11.3.8。`GetStorageInfo` 对任何 ID 都返回它。 */
        const val INVALID_STORAGE_ID: Int = 0x2008

        /** 出处：0x2013 = `PTP_RC_StoreNotAvailable`；对照 `MtpConstants.RESPONSE_STORE_NOT_AVAILABLE`；
         *  PIMA 15740 §11.3.19。
         *
         *  真机观测：`GetObjectHandles` 传**非法 format（`0xffffffff`）**时返回它（v6 §1.4）；
         *  按具体 storage 精查则回归 `0x2006`（定稿 §4.1）。
         *
         *  ⚠️ 名字极易被误读成「卡没插」——**实际含义是「这条查询条件被拒」**，与存储卡在位与否无关。
         *  也**不是** `0x2014 SpecificationByFormatUnsupported`，更不是 `0x201E SessionAlreadyOpened`。 */
        const val STORE_NOT_AVAILABLE: Int = 0x2013

        /** 出处：0x201D = `PTP_RC_InvalidParameter`；对照 `MtpConstants.RESPONSE_INVALID_PARAMETER`；
         *  PIMA 15740 §11.3.29。
         *
         *  真机语义：写入不成立（写「容器属性」`0x..X0` 必被它拒，定稿 §3.7）。
         *  ⚠️ 邻居 `0x201C` 是 `InvalidDevicePropValue`（属性值不合法），别混。 */
        const val INVALID_PARAMETER: Int = 0x201D
    }

    // ────────────────────────────── 事件码 ──────────────────────────────

    /** 实际会消费的事件（其余声明事件按码丢弃/计数，定稿 §1.4 / §6 约束 4）。 */
    object Event {
        /** 出处：0x4006 = `DevicePropChanged`；PIMA 15740 EventCode 表 / §12.5.6；
         *  dump 的 events 含此码。实测参数 `[0x5001]`（电量）。 */
        const val DEVICE_PROP_CHANGED: Int = 0x4006

        /** 0xC108 = `ObjectAdded`（私有码，`params[0]` = 新对象 handle）。
         *  出处：真机实测（定稿 §1.4）；v6 §1.6 记 libgphoto2 定义为 `PTP_EC_PANASONIC_ObjectAdded`。
         *  **总线上没有标准 0x4002**，拍摄回传只能靠它。 */
        const val OBJECT_ADDED: Int = 0xC108

        /** 0xC101 = 噪声（一张照片来 5 次）。出处：真机实测（定稿 §1.4）；厂商私有码，规范表无。
         *  语义未定义 → 只允许丢弃/计数，不得据此改状态。 */
        const val NOISE_STATE_CHANGED: Int = 0xC101

        /** 0xC107 = 噪声/容量变化（一张照片来 7 次）。出处：真机实测（定稿 §1.4）；厂商私有码，规范表无。 */
        const val NOISE_CAPACITY_CHANGED: Int = 0xC107
    }

    // ────────────────────────────── 对象格式 ──────────────────────────────

    /** 对象格式码。⚠️ 只有一半是标准码，见每条的出处说明。 */
    object Format {
        /** 出处：0x3001 = `Association`（目录）；PIMA 15740 ObjectFormatCode 表（`0x3001 A Association`）。 */
        const val ASSOCIATION: Int = 0x3001

        /** 出处：0x3006 = `DPOF`（Digital Print Order Format file, text）；PIMA 15740 ObjectFormatCode 表（DPOF）；dump 的 imageFormats 含 0x3006。
         *  ⚠️ **不是照片格式**：禁止据此进图库 / 出缩略图 / 算照片数量。 */
        const val DPOF: Int = 0x3006

        /** 出处：0x3801 = `EXIF/JPEG`；PIMA 15740 ObjectFormatCode 表（`0x3801 I EXIF/JPEG`）；
         *  dump 的 imageFormats 含 0x3801。**唯一无争议的照片格式码**，可按它硬判「只回传 JPG」。 */
        const val JPEG: Int = 0x3801

        /** 出处：0x3800 —— **非标准码**，见下。PIMA 15740 表里它是 `Undefined / Unknown image object`，
         *  Panasonic 拿它装 RW2。出处：真机 dump（imageFormats 含 0x3800）；定稿 §1.5.1 / §4.2。
         *  用途仅限「Lumix 默认值」：RAW 判定必须走 `PtpQuirks.rawFormatCodes`，
         *  **禁止在业务层直接比这个常量**（别家可能用同一个"未定义"码装别的东西）。 */
        const val RAW_RW2: Int = 0x3800

        /** 出处：0x300D —— **PIMA 15740 表内无此码**（MTP 扩展码位）。出处：真机 dump（imageFormats 含 0x300D）；
         *  定稿 §1.5.1 / §4.2。同样放 quirks，不要全局硬判。 */
        const val MOV: Int = 0x300D
    }

    // ────────────────────────────── 属性 ID ──────────────────────────────

    /**
     * Panasonic 私有属性 ID（u32，用 Long 承载）。
     *
     * 出处：**真机实测**（DC-S1 固件 2.4），见定稿 §3 各小节；描述符 dump 见 `fixtures/desc_*.hex`、
     * 值 dump 见 `fixtures/value_*.hex`。**档位数（枚举项数）属 T03 解析结果，此处不断言。**
     *
     * 命名约定：`*_CONTAINER` 是容器属性（`0x..X0`，只读、写必 `0x201D`），
     * `*_VALUE` 是值属性（`0x..X1`，唯一可写入口，写完必须读回校验）。
     */
    object Prop {
        /** 出处：真机实测 `fixtures/desc_iso.hex`；定稿 §3.3（档位数由 T03 解析，勿在此断言）。 */
        const val ISO_CONTAINER: Long = 0x02000020L

        /** 出处：真机实测 `fixtures/value_iso.hex`；定稿 §3.3。`0xFFFFFFFF` = AUTO。 */
        const val ISO_VALUE: Long = 0x02000021L

        /** 出处：真机实测 `fixtures/desc_shutter.hex`；定稿 §3.1（档位数由 T03 解析，勿在此断言）。 */
        const val SHUTTER_CONTAINER: Long = 0x02000030L

        /** 出处：真机实测 `fixtures/value_shutter.hex`；定稿 §3.1。`0xFFFFFFFF` = BULB。 */
        const val SHUTTER_VALUE: Long = 0x02000031L

        /** 出处：真机实测 `fixtures/desc_aperture.hex`；定稿 §3.2（档位数由 T03 解析，勿在此断言）。 */
        const val APERTURE_CONTAINER: Long = 0x02000040L

        /** 出处：真机实测 `fixtures/value_aperture.hex`；定稿 §3.2。`value / 10` = f 值；`0xFFFE` = 无镜头数据。 */
        const val APERTURE_VALUE: Long = 0x02000041L

        /** 出处：真机实测（定稿 §3.2 的条目链：`0x41` 当前值、`0x42` 当前/最大）。 */
        const val APERTURE_MAX: Long = 0x02000042L

        /** 出处：真机实测 `fixtures/desc_whitebalance.hex`；定稿 §3.5（视频档 15 项 / 拍照档 16 项）。 */
        const val WHITE_BALANCE_CONTAINER: Long = 0x02000050L

        /** 出处：真机实测（定稿 §3.5）；`2` = AWB。 */
        const val WHITE_BALANCE_VALUE: Long = 0x02000051L

        /** 出处：真机实测 `fixtures/desc_ev.hex`；定稿 §3.4（步长 1/3 EV，±3.0 EV）。 */
        const val EXPOSURE_COMPENSATION_CONTAINER: Long = 0x02000060L

        /** 出处：真机实测（定稿 §3.4）；负值 = `0x8000 | n`。 */
        const val EXPOSURE_COMPENSATION_VALUE: Long = 0x02000061L

        /** 出处：真机实测（定稿 §3.6，描述符 7 项）。 */
        const val AF_MODE: Long = 0x02000071L

        /** 出处：真机实测 `fixtures/value_mode_pos.hex`；定稿 §2.1 / §2.2 / §3.6。
         *  拍照 0/1/2/3，视频恒 5，iA 7。 */
        const val MODE_POS: Long = 0x02000082L

        /** 出处：真机实测 `fixtures/desc_photostyle.hex`；定稿 §3.6（`0` 是非法值）。 */
        const val PHOTO_STYLE: Long = 0x02000083L

        /** 出处：真机实测（定稿 §9：只记录存在，未取描述符）。视频分辨率/帧率配置。 */
        const val VIDEO_RESOLUTION: Long = 0x06000010L

        /** 出处：真机实测（定稿 §3.6，4 项：0,1,2,3）。影片模式。 */
        const val VIDEO_MOVIE_MODE: Long = 0x06000011L

        /** 出处：真机实测（定稿 §3.6，4 项：0,2,3,5）。录制模式。 */
        const val VIDEO_RECORD_MODE: Long = 0x06000014L

        /** 出处：0x5001 = `BatteryLevel`；PIMA 15740 DevicePropCode 表（BatteryLevel）；
         *  dump 的 DevicePropertiesSupported 唯一一项就是它。 */
        const val BATTERY_LEVEL: Long = 0x0000_5001L
    }

    // ─────────────────────────── opcode 参数常量 ───────────────────────────

    /**
     * 私有 opcode 的参数常量。
     *
     * 出处：**真机实测**（DC-S1 固件 2.4），见定稿 §1.3 / §5。
     *
     * 这些值占用 `0x030000xx` / `0x0Dxxxxxx` 空间，与 [Prop] 的属性 ID 数值相邻但语义完全不同
     * —— 不要按数值猜用途，按下面的语义用。
     */
    object Param {
        /** 出处：真机实测（定稿 §1.3 / §5.1）。语义：`0x9404` 的快门参数。
         *  ⚠️ **错用到 `0x9405`（AF/AE 控制）上会返回 `0x2002`** —— 曾因此误判「S1 不支持 AF」。 */
        const val CAPTURE_SHUTTER: Long = 0x03000011L

        /** 出处：真机实测（定稿 §5.2）。语义：`0x9405` 的 LockAE（锁曝光）。 */
        const val AF_LOCK_AE: Long = 0x03000021L

        /** 出处：真机实测（定稿 §5.2）。语义：`0x9405` 的 LockAF（锁对焦）。 */
        const val AF_LOCK_AF: Long = 0x03000022L

        /** 出处：真机实测（定稿 §5.2）。语义：`0x9405` 的 LockAFAE（对焦 + 曝光一起锁）。 */
        const val AF_LOCK_AE_AF: Long = 0x03000023L

        /** 出处：真机实测（定稿 §5.2，**实测真合焦**）。语义：`0x9405` 的 AF OneShot（单次对焦）。 */
        const val AF_ONESHOT: Long = 0x03000024L

        /** 出处：真机实测（定稿 §5.2）。语义：`0x9405` 的 Lock Clear（解除锁定）。 */
        const val AF_LOCK_CLEAR: Long = 0x03000025L

        /** 出处：真机实测（定稿 §1.3 / §5.3）。语义：`0x9412` 的取景开。 */
        const val LIVEVIEW_ON: Long = 0x0D000010L

        /** 出处：真机实测（定稿 §1.3 / §5.3）。语义：`0x9412` 的取景关。 */
        const val LIVEVIEW_OFF: Long = 0x0D000011L

        /** 出处：真机实测（v7 §10.1）。语义：`0x9415` 的写取景格式参数；DATA 16 B，
         *  **`x` 必须照抄枚举元组**（自拼 `x=0` 会让之后每帧 `0x9706` 返回 `0x2019`）。
         *  ⚠️ 与读参数 [LIVEVIEW_FORMAT_TABLE] `0x0D800012` **只差一位**：这个是「写」，那个是「读」，别写反。 */
        const val LIVEVIEW_FORMAT_SET: Long = 0x0D800011L

        /** 出处：真机实测（v7 §10.1）；dump `fixtures/liveview_formats.hex`。语义：`0x9414` 的读取景格式表。
         *  ⚠️ 与写参数 [LIVEVIEW_FORMAT_SET] `0x0D800011` **只差一位**：这个是「读」，那个是「写」，别写反。 */
        const val LIVEVIEW_FORMAT_TABLE: Long = 0x0D800012L
    }

    // ────────────────────────────── 模式位取值 ──────────────────────────────

    /** [Prop.MODE_POS] 的合法取值。出处：真机实测（定稿 §2.1 / §2.2 / §3.6）。 */
    object ModePos {
        /** 拍照 P 档。 */
        const val P: Long = 0L

        /** 拍照 A 档。 */
        const val A: Long = 1L

        /** 拍照 S 档。 */
        const val S: Long = 2L

        /** 拍照 M 档。 */
        const val M: Long = 3L

        /** 视频档：四子档恒为 5，靠快门/光圈描述符 count 组合推断（定稿 §2.2 / §2.4）。 */
        const val VIDEO: Long = 5L

        /** iA 档。 */
        const val INTELLIGENT_AUTO: Long = 7L
    }
}
