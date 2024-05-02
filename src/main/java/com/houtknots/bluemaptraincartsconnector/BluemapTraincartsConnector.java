package com.houtknots.bluemaptraincartsconnector;

import java.util.List;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Set;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Location;


public final class BluemapTraincartsConnector extends JavaPlugin {

    Boolean BLUEMAPREADY = false;
    HashMap<String, MarkerSet> WORLDS = new HashMap<>();

    @Override
    public void onEnable() {
        // Create the config.yml file
        saveResource("config.yml", false);

        // Save the PIO Asset to make it accessible for Bluemap
        savePIOAsset("btc-minecart-pio.png", "bluemap/web/assets/btc-minecart-pio.png");
        savePIOAsset("btc-storage-pio.png", "bluemap/web/assets/btc-storage-pio.png");

        // Wait until Bluemap is Enabled before allowing execution
        BlueMapAPI.onEnable(api -> {
            getLogger().info("Bluemap is ready! Starting execution");
            this.BLUEMAPREADY = true;
        });

        long updateInterval;
        updateInterval = getConfig().getLong("updateInterval") * 20;

        // Create a task timer to update the Train Locations
        new BukkitRunnable() {
            @Override
            public void run() {
                updateTrainLocations();
            }
        }.runTaskTimer(this, 0, updateInterval);

    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    // Save resource file in the server to make it accessible for Bluemap
    private void savePIOAsset(String resourceAsset, String outputPath) {
        File outFile = new File(getDataFolder().getParentFile().getParent(), outputPath);
        if (!outFile.exists()) {
            outFile.getParentFile().mkdirs(); // Create directories if they do not exist
            try (InputStream in = getResource(resourceAsset); OutputStream out = new FileOutputStream(outFile)) {
                if (in == null) {
                    getLogger().severe("Resource file " + resourceAsset + " not found in plugin!");
                    return;
                }
                byte[] buffer = new byte[1024];
                int length;
                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
            } catch (Exception e) {
                getLogger().severe("Failed to save PIO asset file: " + e.getMessage());
            }
        }
    }

    // Create the Markerset required to place the TrainCarts markers in
    private void prepMarkerSet(BlueMapAPI api, String world){
        String markerSetId = getConfig().getString("markerSetID");
        String markerSetLabel = getConfig().getString("markerSetLabel");


        // Build the TrainCarts MarkerSet
        getLogger().info("Creating Markerset " + markerSetId + " with label " + markerSetLabel + " in map " + world);
        final MarkerSet markerSet = MarkerSet.builder()
                .label(markerSetLabel)
                .defaultHidden(getConfig().getBoolean("markerSetDefaultHidden"))
                .build();
        api.getWorld(world).map(BlueMapWorld::getMaps).ifPresent(maps -> maps.forEach(map -> map.getMarkerSets().put(markerSetId, markerSet)));

        this.WORLDS.put(world, markerSet);
    }


    private boolean checkStorageTags(Set<String> trainTags){
        List<String> connectorStorageTags = getConfig().getStringList("connectorStorageTags");
        for (String tag : connectorStorageTags) {
            if (trainTags.contains(tag)) {
                return true;  // Return true as soon as any matching tag is found
            }
        }
        return false;
    }

    // Check if the Train tags match the tags assigned in config.yml
    private boolean checkTags(Set<String> trainTags) {
        // Retrieve the list of connector tags from configuration
        List<String> connectorTags = getConfig().getStringList("connectorTags");

        // Check if any tag in 'connectorTags' is present in 'trainTags'
        for (String tag : connectorTags) {
            if (trainTags.contains(tag)) {
                return true;  // Return true as soon as any matching tag is found
            }
        }

        // Check if any tag in 'connectorStorageTags' is present in 'trainTags'
        return this.checkStorageTags(trainTags);
    }


    // Update the Train Locations on the BlueMap
    private void updateTrainLocations() {

        if(!this.BLUEMAPREADY){
            return;
        }

        for (MinecartGroup group : MinecartGroup.getGroups()) {
            if (!group.isEmpty()) {

                // Retrieve information about the Train
                Location location;
                location = group.head().getEntity().getLocation();
                String world = location.getWorld().getName();
                String uuid = String.valueOf(group.head().getProperties().getUUID());
                String TrainName = group.head().getProperties().getTrainProperties().getDisplayName();
                Set<String> trainTags = (Set<String>) group.head().getProperties().getTrainProperties().getTags();

                // Is DisplayName is not set use the TrainName
                if (TrainName.isEmpty()) {
                    TrainName = group.head().getProperties().getTrainProperties().getTrainName();
                }

                // If connectorShowTaggedOnly is false skip Tag checking
                if (getConfig().getBoolean("connectorShowTaggedOnly")) {
                    // Skip train if it does not have the correct tags configured!
                    if (!this.checkTags(trainTags)) {
                        continue;
                    }
                }

                // Select the markerIcon to use
                String markerIcon = null;
                if (this.checkStorageTags(trainTags)) {
                    markerIcon = getConfig().getString("connectorStorageTrainIcon");
                } else {
                    markerIcon = getConfig().getString("connectorTrainIcon");
                }

                String finalTrainName = TrainName;
                String finalMarkerIcon = markerIcon;

                // Update the BlueMap Markers
                BlueMapAPI.getInstance().ifPresent(bluemapApi -> {

                    // Check if a Markerset exists for the world, otherwise create it
                    if (!this.WORLDS.containsKey(world)) {
                        this.prepMarkerSet(bluemapApi, world);
                        return;
                    }

                    final Vector3d pos = new Vector3d(location.getX(), location.getY() + 0.25, location.getZ());
                    final POIMarker marker = POIMarker.builder()
                            .position(pos)
                            .label(finalTrainName)
                            .icon(finalMarkerIcon, 10, 10 )
                            .build();
                    this.WORLDS.get(world).getMarkers().put(uuid, marker);
                });
            }
        }

        //Unmark Destroyed or Unloaded trains
        this.WORLDS.keySet().forEach(this::unmarkDestroyedTrains);
    }

    private void unmarkDestroyedTrains(String world){
        Set<String> activeTrains = new HashSet<>();

        // Loop through the trains to collect active UUIDs
        for (MinecartGroup group : MinecartGroup.getGroups()) {
            if (!group.isEmpty()) {
                Location location;
                location = group.head().getEntity().getLocation();
                String trainWorld = location.getWorld().getName();
                Set<String> trainTags = (Set<String>) group.head().getProperties().getTrainProperties().getTags();
                if (world.equals(trainWorld)) {
                    // If connectorShowTaggedOnly is false skip Tag checking
                    if (getConfig().getBoolean("connectorShowTaggedOnly")) {
                        // Skip train if it does not have the correct tags configured!
                        if (!this.checkTags(trainTags)) {
                            continue;
                        }
                    }
                    activeTrains.add(String.valueOf(group.head().getProperties().getUUID()));
                }
            }
        }

        // Access BlueMap API to manage markers
        BlueMapAPI.getInstance().ifPresent(bluemapApi -> {
            bluemapApi.getWorld(world).ifPresent(bmWorld -> {
                bmWorld.getMaps().forEach(map -> {
                    MarkerSet markerSet = this.WORLDS.get(world);
                    if (markerSet != null) {
                        // Collect all markers that need to be removed
                        Set<String> toRemove = new HashSet<>();
                        markerSet.getMarkers().keySet().forEach(markerKey -> {
                            if (!activeTrains.contains(markerKey)) {
                                toRemove.add(markerKey);
                            }
                        });

                        // Remove the collected markers
                        toRemove.forEach(markerSet.getMarkers()::remove);
                    }
                });
            });
        });
    }
}
