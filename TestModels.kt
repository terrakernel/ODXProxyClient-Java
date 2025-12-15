package io.odxproxy

import io.odxproxy.model.OdxMany2One
import io.odxproxy.model.OdxVariant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class ResPartner(
    val id: Int,
    val name: String,
    @SerialName("company_id")
    val company: OdxMany2One,
    val ref: OdxVariant<String>,
    val email: OdxVariant<String>
)