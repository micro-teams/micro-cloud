/*
 *  Description: Super-admin management of network segments: create / list (optionally by placement) /
 *               get / update / delete. A network binds to a placement (validated on create) and
 *               carries an IPv4 range whose size is reported read-only. Allocation tracking arrives
 *               with machine provisioning; allocatedCount is 0 until then.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.network

import app.microteams.microcloud.common.helper.PageHelper
import app.microteams.microcloud.machine.placement.PlacementService
import app.microteams.microcloud.model.*
import org.rucca.cheese.common.error.BadRequestError
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

fun Network.toDTO(allocatedCount: Int) =
    NetworkDTO(
        id = this.id!!,
        placementId = this.placementId!!,
        name = this.name,
        startIp = this.startIp!!,
        endIp = this.endIp!!,
        gateway = this.gateway!!,
        prefixLength = this.prefixLength!!,
        bridge = this.bridge!!,
        status =
            when (this.status) {
                NetworkStatus.ACTIVE -> NetworkStatusDTO.active
                NetworkStatus.DISABLED -> NetworkStatusDTO.disabled
            },
        totalCount = Ipv4.rangeSize(this.startIp!!, this.endIp!!).toInt(),
        allocatedCount = allocatedCount,
        createdAt = this.createdAt?.atOffset(java.time.ZoneOffset.UTC),
    )

fun NetworkStatusDTO.toEntity() =
    when (this) {
        NetworkStatusDTO.active -> NetworkStatus.ACTIVE
        NetworkStatusDTO.disabled -> NetworkStatus.DISABLED
    }

@Service
@Transactional
class NetworkService(
    private val networkRepository: NetworkRepository,
    private val ipAllocationRepository: IpAllocationRepository,
    private val placementService: PlacementService,
) {
    fun getNetwork(id: IdType): Network =
        networkRepository.findById(id).orElseThrow { NotFoundError("network", id) }

    private fun Network.toDTOWithCount() =
        toDTO(ipAllocationRepository.countByNetworkId(this.id!!).toInt())

    fun getNetworkDTO(id: IdType): NetworkDTO = getNetwork(id).toDTOWithCount()

    fun createNetwork(request: CreateNetworkRequestDTO): NetworkDTO {
        placementService.getPlacement(request.placementId) // 404 if the placement is unknown
        Ipv4.rangeSize(request.startIp, request.endIp) // validates the range + ordering
        Ipv4.parse(request.gateway)
        if (request.prefixLength !in 0..32) throw BadRequestError("prefixLength must be in 0..32")
        return networkRepository
            .save(
                Network(
                    placementId = request.placementId,
                    name = request.name,
                    startIp = request.startIp,
                    endIp = request.endIp,
                    gateway = request.gateway,
                    prefixLength = request.prefixLength,
                    bridge = request.bridge,
                )
            )
            .toDTOWithCount()
    }

    fun listNetworks(
        placementId: IdType?,
        pageStart: IdType?,
        pageSize: Int,
    ): Pair<List<NetworkDTO>, PageDTO> {
        val all =
            (if (placementId == null) networkRepository.findAll()
                else networkRepository.findByPlacementId(placementId))
                .sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTOWithCount() } to info
    }

    fun updateNetwork(id: IdType, request: UpdateNetworkRequestDTO): NetworkDTO {
        val network = getNetwork(id)
        request.name?.let { network.name = it }
        request.gateway?.let {
            Ipv4.parse(it)
            network.gateway = it
        }
        request.prefixLength?.let {
            if (it !in 0..32) throw BadRequestError("prefixLength must be in 0..32")
            network.prefixLength = it
        }
        request.bridge?.let { network.bridge = it }
        request.status?.let { network.status = it.toEntity() }
        return networkRepository.save(network).toDTOWithCount()
    }

    fun deleteNetwork(id: IdType) {
        networkRepository.delete(getNetwork(id))
    }

    // ---- IP allocation (used by machine provisioning) ----

    /** Active networks bound to a placement that still have a free address, lowest id first. */
    fun networksWithFreeIp(placementId: IdType): List<Network> =
        networkRepository
            .findByPlacementId(placementId)
            .filter { it.status == NetworkStatus.ACTIVE }
            .filter { network ->
                val total = Ipv4.rangeSize(network.startIp!!, network.endIp!!)
                ipAllocationRepository.countByNetworkId(network.id!!) < total
            }
            .sortedBy { it.id }

    /**
     * Lease the lowest free address in the network to a machine. The unique (network_id, ip)
     * constraint is the real guard against a double lease under concurrency; this picks a
     * candidate.
     */
    fun allocateIp(networkId: IdType, machineId: IdType): String {
        val network = getNetwork(networkId)
        val taken = ipAllocationRepository.findByNetworkId(networkId).mapNotNull { it.ip }.toSet()
        val start = Ipv4.parse(network.startIp!!)
        val end = Ipv4.parse(network.endIp!!)
        var candidate = start
        while (candidate <= end) {
            val ip = Ipv4.format(candidate)
            if (ip !in taken) {
                ipAllocationRepository.save(
                    IpAllocation(networkId = networkId, machineId = machineId, ip = ip)
                )
                return ip
            }
            candidate++
        }
        throw BadRequestError("network $networkId has no free address")
    }

    /** Release every address held by a machine (on destroy). */
    fun releaseIpsFor(machineId: IdType) {
        ipAllocationRepository.deleteAll(ipAllocationRepository.findByMachineId(machineId))
    }
}
