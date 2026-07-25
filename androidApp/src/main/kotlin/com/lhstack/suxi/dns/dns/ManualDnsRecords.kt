package com.lhstack.suxi.dns.dns

/**
 * 手动解析支持的查询类型。
 *
 * [wireType] 为 DNS 协议中的 TYPE 值。[conflictsWith] 用于校验多选时的冲突：
 * A / AAAA / CNAME 三者互斥（同名不应既是 CNAME 又是 A/AAAA），只允许选其一。
 * 其余类型（MX/TXT/NS/SOA/SRV/PTR）与任何类型都不冲突，可任意组合。
 */
enum class ManualQueryType(
    val displayName: String,
    val wireType: Int,
) {
    A("A", 1),
    AAAA("AAAA", 28),
    CNAME("CNAME", 5),
    MX("MX", 15),
    TXT("TXT", 16),
    NS("NS", 2),
    SOA("SOA", 6),
    SRV("SRV", 33),
    PTR("PTR", 12),
    ;

    /** 该类型是否用于反向解析（输入 IP）。 */
    val isReverse: Boolean get() = this == PTR
}

/** A/AAAA/CNAME 互斥组：同一域名的这三种正向记录不应同时手动查询。 */
private val ADDRESS_CONFLICT_GROUP = setOf(
    ManualQueryType.A,
    ManualQueryType.AAAA,
    ManualQueryType.CNAME,
)

/**
 * 校验多选类型是否存在冲突。
 * 返回冲突描述；无冲突返回 null。
 */
fun findManualTypeConflict(types: Set<ManualQueryType>): String? {
    val addressTypes = types.filter { it in ADDRESS_CONFLICT_GROUP }
    if (addressTypes.size > 1) {
        val names = addressTypes.joinToString(" / ") { it.displayName }
        return "$names 互斥，同一域名只能选其中一种"
    }
    if (ManualQueryType.PTR in types && types.size > 1) {
        return "PTR（反向解析）只能单独查询"
    }
    return null
}

/** 单条手动解析结果记录（已按类型结构化）。 */
data class ManualDnsRecord(
    val name: String,
    val type: ManualQueryType,
    val ttlSeconds: Int,
    /** 面向展示的记录值文本。 */
    val value: String,
)

/** 单个查询类型的解析结果。 */
data class ManualQueryResult(
    val type: ManualQueryType,
    val upstream: String,
    val elapsedMs: Long,
    val records: List<ManualDnsRecord>,
    val rcodeMessage: String?,
    val error: String?,
)
