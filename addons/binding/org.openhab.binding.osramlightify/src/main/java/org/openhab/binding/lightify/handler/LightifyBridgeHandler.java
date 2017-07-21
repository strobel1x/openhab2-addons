/*
 * Copyright (c) 2014-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.osramlightify.handler;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.Command;

import org.openhab.binding.osramlightify.handler.LightifyBridgeConfiguration;
import org.openhab.binding.osramlightify.internal.LightifyConnector;
import org.openhab.binding.osramlightify.internal.discovery.LightifyDeviceDiscoveryService;

import org.osgi.framework.ServiceRegistration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openhab.binding.osramlightify.internal.messages.LightifyMessage;

/**
 * The {@link org.eclipse.smarthome.core.thing.binding.BridgeHandler} implementation to handle commands
 * and status of the OSRAM Lightify gateway device. This handler uses a native implementation of the
 * Lightify proprietary protocol to communicate with the Lightify gateway, therefore no internet connection
 * is necessary to utilize the OSRAM public API.
 *
 * @author Mike Jagdis - Initial contribution
 */
public final class LightifyBridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(LightifyBridgeHandler.class);

    private ServiceRegistration<?> serviceRegistration;
    private LightifyDeviceDiscoveryService discoveryService;

    private LightifyBridgeConfiguration configuration = null;
    private LightifyConnector connector;

    public LightifyBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void initialize() {
        // In theory we can do this. In practice there are races in the core that
        // mean we have to hold off a little.
        thingUpdated(getThing());
        registerDeviceDiscoveryService();
        updateStatus(ThingStatus.UNKNOWN);
        connector = new LightifyConnector(this);
    }

    @Override
    public void dispose() {
        if (connector != null) {
            logger.debug("Interrupt Lightify connector");
            connector.interrupt();
            try {
                connector.join();
            } catch (InterruptedException ie) {
            }
        }
        connector = null;

        deregisterDeviceDiscoveryService();

        super.dispose();
    }

    @Override
    public void thingUpdated(Thing thing) {
        configuration = getConfigAs(LightifyBridgeConfiguration.class);

        configuration.minPollIntervalNanos = TimeUnit.MILLISECONDS.toNanos((long) (configuration.minPollInterval * 1000 + 0.5));
        configuration.maxPollIntervalNanos = TimeUnit.MILLISECONDS.toNanos((long) (configuration.maxPollInterval * 1000 + 0.5));

        for (Thing child : ((Bridge) thing).getThings()) {
            ThingHandler thingHandler = child.getHandler();
            if (thingHandler != null) {
                thingHandler.thingUpdated(child);
            }
        }
    }

    public void setStatusOnline() {
        updateStatus(ThingStatus.ONLINE);
    }

    public void setStatusOffline(ThingStatusDetail detail) {
        updateStatus(ThingStatus.OFFLINE, detail);
    }

    public void sendMessage(LightifyMessage message) {
        connector.sendMessage(message);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
    }

    public LightifyBridgeConfiguration getConfiguration() {
        return configuration;
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public LightifyDeviceDiscoveryService getDiscoveryService() {
        return discoveryService;
    }

    private void registerDeviceDiscoveryService() {
        discoveryService = new LightifyDeviceDiscoveryService(this);
        serviceRegistration = bundleContext.registerService(DiscoveryService.class, discoveryService, null);
    }

    private void deregisterDeviceDiscoveryService() {
        if (serviceRegistration != null) {
            serviceRegistration.unregister();
            serviceRegistration = null;
        }
    }
}
