/*
 *  Description: Machine-type management: admin create / update / delete, tenant-and-admin read.
 *               Create/update validate the referenced placements and the spec ranges (min <= max).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.type

import app.microteams.microcloud.common.helper.PageHelper
import app.microteams.microcloud.machine.placement.PlacementService
import app.microteams.microcloud.model.*
import org.rucca.cheese.common.error.BadRequestError
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

fun MachineType.toDTO() =
    MachineTypeDTO(
        id = this.id!!,
        name = this.name!!,
        description = this.description,
        placementIds = this.placementIds.toList(),
        coresMin = this.coresMin!!,
        coresMax = this.coresMax!!,
        memoryMbMin = this.memoryMbMin!!,
        memoryMbMax = this.memoryMbMax!!,
        diskGbMin = this.diskGbMin!!,
        diskGbMax = this.diskGbMax!!,
        status =
            when (this.status) {
                MachineTypeStatus.ACTIVE -> MachineTypeStatusDTO.active
                MachineTypeStatus.DISABLED -> MachineTypeStatusDTO.disabled
            },
        createdAt = this.createdAt?.atOffset(java.time.ZoneOffset.UTC),
    )

fun MachineTypeStatusDTO.toEntity() =
    when (this) {
        MachineTypeStatusDTO.active -> MachineTypeStatus.ACTIVE
        MachineTypeStatusDTO.disabled -> MachineTypeStatus.DISABLED
    }

@Service
@Transactional
class MachineTypeService(
    private val machineTypeRepository: MachineTypeRepository,
    private val placementService: PlacementService,
) {
    fun getType(id: IdType): MachineType =
        machineTypeRepository.findById(id).orElseThrow { NotFoundError("machine-type", id) }

    fun getTypeDTO(id: IdType): MachineTypeDTO = getType(id).toDTO()

    private fun validatePlacements(placementIds: List<IdType>) {
        placementIds.forEach { placementService.getPlacement(it) } // 404 on any unknown placement
    }

    private fun validateRange(min: Int, max: Int, label: String) {
        if (min > max) throw BadRequestError("$label: min must not exceed max")
        if (min <= 0) throw BadRequestError("$label: min must be positive")
    }

    fun createType(request: CreateMachineTypeRequestDTO): MachineTypeDTO {
        validatePlacements(request.placementIds)
        validateRange(request.coresMin, request.coresMax, "cores")
        validateRange(request.memoryMbMin, request.memoryMbMax, "memoryMb")
        validateRange(request.diskGbMin, request.diskGbMax, "diskGb")
        return machineTypeRepository
            .save(
                MachineType(
                    name = request.name,
                    description = request.description,
                    placementIds = request.placementIds.toMutableList(),
                    coresMin = request.coresMin,
                    coresMax = request.coresMax,
                    memoryMbMin = request.memoryMbMin,
                    memoryMbMax = request.memoryMbMax,
                    diskGbMin = request.diskGbMin,
                    diskGbMax = request.diskGbMax,
                )
            )
            .toDTO()
    }

    fun listTypes(pageStart: IdType?, pageSize: Int): Pair<List<MachineTypeDTO>, PageDTO> {
        val all = machineTypeRepository.findAll().sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun updateType(id: IdType, request: UpdateMachineTypeRequestDTO): MachineTypeDTO {
        val type = getType(id)
        request.name?.let { type.name = it }
        request.description?.let { type.description = it }
        request.placementIds?.let {
            validatePlacements(it)
            type.placementIds = it.toMutableList()
        }
        request.coresMin?.let { type.coresMin = it }
        request.coresMax?.let { type.coresMax = it }
        request.memoryMbMin?.let { type.memoryMbMin = it }
        request.memoryMbMax?.let { type.memoryMbMax = it }
        request.diskGbMin?.let { type.diskGbMin = it }
        request.diskGbMax?.let { type.diskGbMax = it }
        validateRange(type.coresMin!!, type.coresMax!!, "cores")
        validateRange(type.memoryMbMin!!, type.memoryMbMax!!, "memoryMb")
        validateRange(type.diskGbMin!!, type.diskGbMax!!, "diskGb")
        request.status?.let { type.status = it.toEntity() }
        return machineTypeRepository.save(type).toDTO()
    }

    fun deleteType(id: IdType) {
        machineTypeRepository.delete(getType(id))
    }
}
