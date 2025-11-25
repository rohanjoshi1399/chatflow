package com.chatflow.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Manages consumer queue partitioning across multiple server instances.
 * 
 * Problem: Currently all instances consume all queues, causing 4x redundant work
 * with 4 instances. Each message is read by ~4 consumers, only 1 deletes successfully.
 * 
 * Solution: Partition queues across instances using consistent hashing so each
 * queue is consumed by exactly one instance at a time.
 * 
 * Example with 4 servers:
 * - Server 1: Rooms 1, 5, 9, 13, 17 (5 rooms)
 * - Server 2: Rooms 2, 6, 10, 14, 18 (5 rooms)
 * - Server 3: Rooms 3, 7, 11, 15, 19 (5 rooms)
 * - Server 4: Rooms 4, 8, 12, 16, 20 (5 rooms)
 */
@Service
@Slf4j
public class ConsumerPartitioningService {

    @Value("${server.id}")
    private String serverId;

    @Value("${consumer.partition.enabled:true}")
    private boolean partitioningEnabled;

    @Value("${consumer.partition.servers:}")
    private String allServersConfig;

    private static final int TOTAL_ROOMS = 20;

    /**
     * Get the list of room IDs this server instance should consume from.
     * 
     * If partitioning disabled, returns all rooms (1-20).
     * If enabled, returns subset based on consistent hashing.
     */
    public List<String> getAssignedRooms() {
        if (!partitioningEnabled || allServersConfig == null || allServersConfig.trim().isEmpty()) {
            log.info("Consumer partitioning disabled, this instance will consume all {} rooms", TOTAL_ROOMS);
            return getAllRooms();
        }

        List<String> allServers = parseServerList(allServersConfig);
        
        if (allServers.isEmpty()) {
            log.warn("No servers configured for partitioning, consuming all rooms");
            return getAllRooms();
        }

        if (!allServers.contains(serverId)) {
            log.warn("Server ID '{}' not found in configured servers: {}. Consuming all rooms.", 
                    serverId, allServers);
            return getAllRooms();
        }

        // Sort for consistent ordering across all instances
        Collections.sort(allServers);
        
        int serverIndex = allServers.indexOf(serverId);
        int totalServers = allServers.size();
        
        List<String> assignedRooms = new ArrayList<>();
        
        // Use modulo distribution for even spread
        for (int room = 1; room <= TOTAL_ROOMS; room++) {
            if ((room - 1) % totalServers == serverIndex) {
                assignedRooms.add(String.valueOf(room));
            }
        }
        
        log.info("Consumer partitioning enabled:");
        log.info("  This server: {} (index {} of {})", serverId, serverIndex, totalServers);
        log.info("  Total servers: {}", allServers);
        log.info("  Assigned rooms: {} ({} rooms)", assignedRooms, assignedRooms.size());
        
        return assignedRooms;
    }

    /**
     * Get all rooms (used when partitioning disabled)
     */
    private List<String> getAllRooms() {
        List<String> rooms = new ArrayList<>();
        for (int i = 1; i <= TOTAL_ROOMS; i++) {
            rooms.add(String.valueOf(i));
        }
        return rooms;
    }

    /**
     * Parse comma-separated server list from configuration
     */
    private List<String> parseServerList(String config) {
        List<String> servers = new ArrayList<>();
        
        if (config != null && !config.trim().isEmpty()) {
            String[] parts = config.split(",");
            for (String server : parts) {
                String trimmed = server.trim();
                if (!trimmed.isEmpty()) {
                    servers.add(trimmed);
                }
            }
        }
        
        return servers;
    }

    /**
     * Calculate expected queue assignment for debugging
     */
    public Map<String, List<String>> calculateAllAssignments() {
        if (!partitioningEnabled || allServersConfig == null || allServersConfig.trim().isEmpty()) {
            return new HashMap<>();
        }

        List<String> allServers = parseServerList(allServersConfig);
        Collections.sort(allServers);
        
        Map<String, List<String>> assignments = new HashMap<>();
        
        for (int serverIndex = 0; serverIndex < allServers.size(); serverIndex++) {
            String server = allServers.get(serverIndex);
            List<String> rooms = new ArrayList<>();
            
            for (int room = 1; room <= TOTAL_ROOMS; room++) {
                if ((room - 1) % allServers.size() == serverIndex) {
                    rooms.add(String.valueOf(room));
                }
            }
            
            assignments.put(server, rooms);
        }
        
        return assignments;
    }

    /**
     * Check if this instance should consume from a specific room
     */
    public boolean shouldConsumeRoom(String roomId) {
        List<String> assignedRooms = getAssignedRooms();
        return assignedRooms.contains(roomId);
    }

    /**
     * Get partition configuration status
     */
    public Map<String, Object> getPartitionStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", partitioningEnabled);
        status.put("serverId", serverId);
        status.put("configuredServers", allServersConfig);
        status.put("assignedRooms", getAssignedRooms());
        
        if (partitioningEnabled) {
            status.put("allAssignments", calculateAllAssignments());
        }
        
        return status;
    }
}