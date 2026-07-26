/*
 *  Description: Offerings: super-admin composes (machine type, zone, template) triples per tenant; a
 *               tenant lists its own. Each DTO embeds the type's spec ranges + the zone/template
 *               names, so the tenant needs no other catalog lookups. Create validates the three exist
 *               and are coherent (the type and zone must share at least one placement, else a machine
 *               from the offering can't land anywhere).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.offering

import app.microteams.microcloud.common.helper.PageHelper
import app.microteams.microcloud.machine.template.TemplateService
import app.microteams.microcloud.machine.type.MachineType
import app.microteams.microcloud.machine.type.MachineTypeService
import app.microteams.microcloud.machine.zone.ZoneService
import app.microteams.microcloud.model.*
import org.rucca.cheese.common.error.BadRequestError
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class OfferingService(
    private val offeringRepository: OfferingRepository,
    private val machineTypeService: MachineTypeService,
    private val zoneService: ZoneService,
    private val templateService: TemplateService,
) {
    fun getOffering(id: IdType): Offering =
        offeringRepository.findById(id).orElseThrow { NotFoundError("offering", id) }

    fun getOfferingDTO(id: IdType): OfferingDTO = getOffering(id).toDTO()

    /** Resolve an offering the tenant is allowed to use: it must belong to them and be active. */
    fun getUsableForTenant(tenantId: IdType, id: IdType): Offering {
        val offering = getOffering(id)
        if (offering.tenantId != tenantId) throw NotFoundError("offering", id)
        if (offering.status != OfferingStatus.ACTIVE)
            throw BadRequestError("offering $id is disabled")
        return offering
    }

    /**
     * The machine type an offering points at (used by machine provisioning for spec + placement).
     */
    fun typeOf(offering: Offering): MachineType =
        machineTypeService.getType(offering.machineTypeId!!)

    fun createOffering(request: CreateOfferingRequestDTO): OfferingDTO {
        val type = machineTypeService.getType(request.machineTypeId)
        val zone = zoneService.getZone(request.zoneId)
        templateService.getTemplate(request.templateId)
        if (type.placementIds.none { it in zone.placementIds })
            throw BadRequestError(
                "machine type ${type.id} and zone ${zone.id} share no placement — nothing to land on"
            )
        return offeringRepository
            .save(
                Offering(
                    tenantId = request.tenantId,
                    machineTypeId = request.machineTypeId,
                    zoneId = request.zoneId,
                    templateId = request.templateId,
                )
            )
            .toDTO()
    }

    fun listOfferings(
        tenantId: IdType?,
        pageStart: IdType?,
        pageSize: Int,
    ): Pair<List<OfferingDTO>, PageDTO> {
        val all =
            (if (tenantId == null) offeringRepository.findAll()
                else offeringRepository.findByTenantId(tenantId))
                .sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun updateOffering(id: IdType, request: UpdateOfferingRequestDTO): OfferingDTO {
        val offering = getOffering(id)
        request.status?.let {
            offering.status =
                when (it) {
                    OfferingStatusDTO.active -> OfferingStatus.ACTIVE
                    OfferingStatusDTO.disabled -> OfferingStatus.DISABLED
                }
        }
        return offeringRepository.save(offering).toDTO()
    }

    fun deleteOffering(id: IdType) {
        offeringRepository.delete(getOffering(id))
    }

    fun Offering.toDTO(): OfferingDTO {
        val type = machineTypeService.getType(this.machineTypeId!!)
        val zone = zoneService.getZone(this.zoneId!!)
        val template = templateService.getTemplate(this.templateId!!)
        return OfferingDTO(
            id = this.id!!,
            tenantId = this.tenantId!!,
            status =
                when (this.status) {
                    OfferingStatus.ACTIVE -> OfferingStatusDTO.active
                    OfferingStatus.DISABLED -> OfferingStatusDTO.disabled
                },
            machineTypeId = type.id!!,
            machineTypeName = type.name!!,
            coresMin = type.coresMin!!,
            coresMax = type.coresMax!!,
            memoryMbMin = type.memoryMbMin!!,
            memoryMbMax = type.memoryMbMax!!,
            diskGbMin = type.diskGbMin!!,
            diskGbMax = type.diskGbMax!!,
            zoneId = zone.id!!,
            zoneName = zone.name!!,
            templateId = template.id!!,
            templateName = template.name!!,
            createdAt = this.createdAt?.atOffset(java.time.ZoneOffset.UTC),
        )
    }
}
