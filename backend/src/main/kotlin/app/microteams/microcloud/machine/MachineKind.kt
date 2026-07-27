/*
 *  Description: MachineKind — the provider + machine form that leads a placement's coordinate, and
 *               that a template's image is built for. Serialized on the wire and stored in the DB as
 *               "<provider>/<form>" (e.g. "proxmox/lxc"). It is the seam for future compute
 *               providers: a new provider/form is a new MachineKind + a new provisioner branch,
 *               while everything above the placement stays kind-agnostic.
 *
 *               A placement declares which kind it can host; a template declares which kind its image
 *               is; the two must match for the template to be usable on the placement. The
 *               provisioner dispatches (pct vs qm vs a future provider) on the placement's kind.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.rucca.cheese.common.error.BadRequestError

enum class MachineKind(val wire: String) {
    PROXMOX_LXC("proxmox/lxc"),
    PROXMOX_VM("proxmox/vm");

    companion object {
        /** Parse a wire value ("proxmox/lxc"); reject anything unknown (used for API input). */
        fun fromWire(value: String): MachineKind =
            entries.firstOrNull { it.wire == value }
                ?: throw BadRequestError(
                    "unknown machine kind '$value'; expected one of " +
                        entries.joinToString(", ") { it.wire }
                )

        /**
         * Like [fromWire] but also accepts the legacy pre-provider names "LXC"/"VM" that older rows
         * stored (before kind carried a provider). Used when reading from the DB so existing
         * template rows load without a data migration; they are rewritten to the wire form on the
         * next save.
         */
        fun fromStored(value: String): MachineKind =
            when (value) {
                "LXC" -> PROXMOX_LXC
                "VM" -> PROXMOX_VM
                else -> fromWire(value)
            }
    }
}

/** Persists [MachineKind] as its wire string, tolerating the legacy "LXC"/"VM" values on read. */
@Converter
class MachineKindConverter : AttributeConverter<MachineKind, String> {
    override fun convertToDatabaseColumn(attribute: MachineKind?): String? = attribute?.wire

    override fun convertToEntityAttribute(dbData: String?): MachineKind? =
        dbData?.let { MachineKind.fromStored(it) }
}
