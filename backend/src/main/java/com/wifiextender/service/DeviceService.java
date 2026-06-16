package com.wifiextender.service;

import com.wifiextender.dto.DeviceDto;
import com.wifiextender.entity.ConnectedDevice;
import com.wifiextender.entity.User;
import com.wifiextender.repository.ConnectedDeviceRepository;
import com.wifiextender.repository.HotspotRepository;
import com.wifiextender.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final ConnectedDeviceRepository deviceRepository;
    private final HotspotRepository         hotspotRepository;
    private final SubscriptionRepository    subscriptionRepository;
    private final DeviceEventPublisher      eventPublisher;

    // ── Get all devices for user ──────────────────────────────────────────────
    public List<DeviceDto.Response> getDevices(Long userId) {
        return deviceRepository.findByUserIdOrderByLastSeenDesc(userId)
                .stream().map(DeviceDto.Response::from).collect(Collectors.toList());
    }

    // ── Get device stats ──────────────────────────────────────────────────────
    public DeviceDto.Stats getStats(Long userId) {
        List<ConnectedDevice> all = deviceRepository.findByUserIdOrderByLastSeenDesc(userId);
        DeviceDto.Stats stats = new DeviceDto.Stats();
        stats.setTotal(all.size());
        stats.setOnline(all.stream().filter(d -> d.isOnline() && !d.isBlocked()).count());
        stats.setBlocked(all.stream().filter(ConnectedDevice::isBlocked).count());
        stats.setOffline(all.stream().filter(d -> !d.isOnline() && !d.isBlocked()).count());
        stats.setTotalBytesSent(all.stream().mapToLong(d -> d.getBytesSent() != null ? d.getBytesSent() : 0).sum());
        stats.setTotalBytesReceived(all.stream().mapToLong(d -> d.getBytesReceived() != null ? d.getBytesReceived() : 0).sum());
        return stats;
    }

    // ── Report single device (upsert) ─────────────────────────────────────────
    @Transactional
    public DeviceDto.Response reportDevice(User user, DeviceDto.ReportRequest req) {
        boolean isNew = false;
        ConnectedDevice device = deviceRepository
                .findByUserIdAndMacAddress(user.getId(), req.getMacAddress())
                .orElseGet(() -> { return new ConnectedDevice(); });

        isNew = device.getId() == null;

        device.setUser(user);
        device.setMacAddress(req.getMacAddress().toUpperCase());
        applyDeviceName(device, req.getDeviceName());
        device.setIpAddress(req.getIpAddress());
        if (req.getVendor() != null && !req.getVendor().isBlank()) {
            device.setVendor(req.getVendor());
        } else if (device.getVendor() == null || device.getVendor().isBlank()) {
            device.setVendor(lookupVendorFromMac(req.getMacAddress()));
        }
        device.setOnline(true);
        device.setLastSeen(LocalDateTime.now());

        if (req.getSignalStrength() != null)
            device.setSignalStrength(req.getSignalStrength());

        if (req.getBytesSent() != null)     device.setBytesSent(req.getBytesSent());
        if (req.getBytesReceived() != null) device.setBytesReceived(req.getBytesReceived());

        if (req.getDeviceType() != null) {
            try {
                device.setDeviceType(ConnectedDevice.DeviceType.valueOf(req.getDeviceType().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                device.setDeviceType(ConnectedDevice.DeviceType.UNKNOWN);
            }
        }

        if (req.getHotspotId() != null)
            hotspotRepository.findById(req.getHotspotId()).ifPresent(device::setHotspot);

        ConnectedDevice saved = deviceRepository.save(device);
        DeviceDto.Response response = DeviceDto.Response.from(saved);

        // Publish WebSocket event
        eventPublisher.publishEvent(user.getId(), isNew ? "CONNECTED" : "UPDATED", response);
        eventPublisher.publishList(user.getId(), getDevices(user.getId()));

        return response;
    }

    // ── Bulk report (desktop app sends all devices at once) ───────────────────
    @Transactional
    public List<DeviceDto.Response> bulkReport(User user, DeviceDto.BulkReportRequest req) {
        List<DeviceDto.ReportRequest> incoming =
                req.getDevices() != null ? req.getDevices() : List.of();

        checkConnectedDeviceLimit(user, incoming);

        Set<String> reportedMacs = new HashSet<>();
        List<DeviceDto.Response> results = new ArrayList<>();
        for (DeviceDto.ReportRequest device : incoming) {
            if (device.getMacAddress() == null || device.getMacAddress().isBlank()) continue;
            String mac = device.getMacAddress().trim().toUpperCase();
            reportedMacs.add(mac);
            if (req.getHotspotId() != null) device.setHotspotId(req.getHotspotId());
            results.add(reportDevice(user, device));
        }

        markMissingDevicesOffline(user.getId(), reportedMacs);

        eventPublisher.publishList(user.getId(), getDevices(user.getId()));
        return results;
    }

    private void markMissingDevicesOffline(Long userId, Set<String> reportedMacs) {
        for (ConnectedDevice device : deviceRepository.findActiveByUserId(userId)) {
            String mac = device.getMacAddress() != null ? device.getMacAddress().toUpperCase() : "";
            if (!reportedMacs.contains(mac)) {
                device.setOnline(false);
                deviceRepository.save(device);
            }
        }
    }

    // ── Toggle block ──────────────────────────────────────────────────────────
    @Transactional
    public DeviceDto.Response toggleBlock(Long deviceId, Long userId) {
        ConnectedDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("Device not found"));
        if (!device.getUser().getId().equals(userId))
            throw new IllegalStateException("Access denied");

        device.setBlocked(!device.isBlocked());
        ConnectedDevice saved = deviceRepository.save(device);
        DeviceDto.Response response = DeviceDto.Response.from(saved);

        String eventType = saved.isBlocked() ? "BLOCKED" : "UNBLOCKED";
        eventPublisher.publishEvent(userId, eventType, response);
        eventPublisher.publishList(userId, getDevices(userId));

        return response;
    }

    // ── Mark device offline ───────────────────────────────────────────────────
    @Transactional
    public void markOffline(Long deviceId, Long userId) {
        deviceRepository.findById(deviceId).ifPresent(d -> {
            if (d.getUser().getId().equals(userId)) {
                d.setOnline(false);
                ConnectedDevice saved = deviceRepository.save(d);
                eventPublisher.publishEvent(userId, "DISCONNECTED", DeviceDto.Response.from(saved));
                eventPublisher.publishList(userId, getDevices(userId));
            }
        });
    }

    // ── Device limit check ────────────────────────────────────────────────────
    /** Limit applies to how many devices are connected right now, not historical total. */
    public void checkConnectedDeviceLimit(User user, List<DeviceDto.ReportRequest> incoming) {
        var subOpt = subscriptionRepository.findActiveByUserId(user.getId());
        if (subOpt.isEmpty()) {
            throw new IllegalStateException("UPGRADE_REQUIRED: No active subscription. Please subscribe to a plan.");
        }
        var sub = subOpt.get();
        if (sub.getPlan().hasUnlimitedDevices()) return;

        long connectedNow = incoming.stream()
                .map(DeviceDto.ReportRequest::getMacAddress)
                .filter(mac -> mac != null && !mac.isBlank())
                .map(mac -> mac.trim().toUpperCase())
                .distinct()
                .count();

        int maxDevices = sub.getPlan().getMaxDevices();
        if (connectedNow > maxDevices) {
            throw new IllegalStateException(
                "UPGRADE_REQUIRED: Device limit reached. Your " + sub.getPlan().getName() +
                " plan allows only " + maxDevices + " device(s). " +
                "Please upgrade your plan to connect more devices."
            );
        }
    }

    private void applyDeviceName(ConnectedDevice device, String incoming) {
        String resolved = incoming != null && !incoming.isBlank() ? incoming.trim() : null;
        if (resolved == null) {
            if (device.getDeviceName() == null || device.getDeviceName().isBlank()) {
                device.setDeviceName("Unknown Device");
            }
            return;
        }
        if (device.getDeviceName() == null || device.getDeviceName().isBlank() || isGenericDeviceName(device.getDeviceName())) {
            device.setDeviceName(resolved);
        } else if (!isGenericDeviceName(resolved)) {
            device.setDeviceName(resolved);
        }
    }

    private boolean isGenericDeviceName(String name) {
        if (name == null || name.isBlank()) return true;
        String n = name.trim();
        return n.equalsIgnoreCase("unknown device")
            || n.matches("(?i)^device\\s+\\d{1,3}(\\.\\d{1,3}){3}$")
            || n.matches("(?i)^device\\s+[0-9a-f:]{11,17}$")
            || n.matches("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$");
    }

    private String lookupVendorFromMac(String mac) {
        if (mac == null || mac.length() < 8) return null;
        String oui = mac.toUpperCase().replace('-', ':').substring(0, 8);
        return switch (oui) {
            case "00:03:93", "A4:83:E7", "F0:DB:E2", "AC:BC:32", "D0:03:4B" -> "Apple";
            case "34:AA:99", "50:01:BB", "94:35:0A", "E4:7C:A6" -> "Samsung";
            case "64:B4:73", "74:23:44", "98:FA:E3", "F8:A4:5F" -> "Xiaomi";
            case "00:1A:11", "94:EB:2C", "F4:F5:E8" -> "Google";
            case "00:1E:06", "94:65:2D", "C0:EE:FB" -> "OnePlus";
            case "00:0E:C6", "10:BF:48" -> "ASUS";
            case "00:04:20", "E4:B3:18" -> "Lenovo";
            default -> null;
        };
    }
}
