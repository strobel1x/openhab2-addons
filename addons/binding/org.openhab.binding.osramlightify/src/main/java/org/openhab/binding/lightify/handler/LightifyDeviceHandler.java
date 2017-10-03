/**
 * Copyright (c) 2014-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.osramlightify.handler;

import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.HSBType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusInfo;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.openhab.binding.osramlightify.LightifyBindingConstants.CHANNEL_COLOR;
import static org.openhab.binding.osramlightify.LightifyBindingConstants.CHANNEL_DIMMER;
import static org.openhab.binding.osramlightify.LightifyBindingConstants.CHANNEL_SWITCH;
import static org.openhab.binding.osramlightify.LightifyBindingConstants.CHANNEL_ABS_TEMPERATURE;
import static org.openhab.binding.osramlightify.LightifyBindingConstants.CHANNEL_TEMPERATURE;

import static org.openhab.binding.osramlightify.LightifyBindingConstants.PROPERTY_MAXIMUM_WHITE_TEMPERATURE;
import static org.openhab.binding.osramlightify.LightifyBindingConstants.PROPERTY_MINIMUM_WHITE_TEMPERATURE;
import static org.openhab.binding.osramlightify.LightifyBindingConstants.PROPERTY_IEEE_ADDRESS;
import static org.openhab.binding.osramlightify.LightifyBindingConstants.THING_TYPE_LIGHTIFY_GROUP;

import org.openhab.binding.osramlightify.handler.LightifyBridgeHandler;
import org.openhab.binding.osramlightify.handler.LightifyDeviceConfiguration;

import org.openhab.binding.osramlightify.internal.LightifyDeviceState;
import org.openhab.binding.osramlightify.internal.exceptions.LightifyException;

import org.openhab.binding.osramlightify.internal.messages.LightifyMessage;
import org.openhab.binding.osramlightify.internal.messages.LightifyGetDeviceInfoMessage;
import org.openhab.binding.osramlightify.internal.messages.LightifyGetProbedTemperatureMessage;
import org.openhab.binding.osramlightify.internal.messages.LightifyListPairedDevicesMessage;
import org.openhab.binding.osramlightify.internal.messages.LightifySetColorMessage;
import org.openhab.binding.osramlightify.internal.messages.LightifySetLuminanceMessage;
import org.openhab.binding.osramlightify.internal.messages.LightifySetSwitchMessage;
import org.openhab.binding.osramlightify.internal.messages.LightifySetTemperatureMessage;

/**
 * The {@link org.eclipse.smarthome.core.thing.binding.ThingHandler} implementation
 * for devices paired with an OSRAM/Sylvania Lightify gateway.
 *
 * @author Mike Jagdis - Initial contribution
 */
public final class LightifyDeviceHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(LightifyDeviceHandler.class);

    private String deviceAddress;
    private LightifyDeviceState lightifyDeviceState = new LightifyDeviceState();
    private boolean stateValid = false;

    private LightifyDeviceConfiguration configuration = null;

    private double whiteTemperatureFactor;

    public LightifyDeviceHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        Thing thing = getThing();

        // The IEEE address is constant.
        deviceAddress = thing.getProperties().get(PROPERTY_IEEE_ADDRESS);

        thingUpdated(thing);

        // N.B. We do not go online or offline here. We do that when we are seen in a
        // list paired/group response for a bridge.
        updateStatus(ThingStatus.UNKNOWN);

        // If we are adding a discovered thing (i.e. one we have no probed capabilities
        // for) we do an immediate poll to trigger probes and bring it online without
        // undue delay.
        if (!thing.getThingTypeUID().equals(THING_TYPE_LIGHTIFY_GROUP)
        && thing.getProperties().get(PROPERTY_MINIMUM_WHITE_TEMPERATURE) == null) {
            LightifyBridgeHandler bridgeHandler = (LightifyBridgeHandler) getBridge().getHandler();

            bridgeHandler.sendMessage(new LightifyListPairedDevicesMessage());
        }
    }

    @Override
    public void dispose() {
        super.dispose();
    }

    @Override
    public void thingUpdated(Thing thing) {
        configuration = getConfigAs(LightifyDeviceConfiguration.class);

        LightifyBridgeHandler bridgeHandler = (LightifyBridgeHandler) getBridge().getHandler();
        LightifyBridgeConfiguration bridgeConfig = bridgeHandler.getConfiguration();

        if (configuration.transitionTime == null) {
            configuration.transitionTime = bridgeConfig.transitionTime;
        }

        if (configuration.transitionToOffTime == null) {
            configuration.transitionToOffTime = bridgeConfig.transitionToOffTime;
        }

        if (configuration.whiteTemperatureMin == null) {
            configuration.whiteTemperatureMin = bridgeConfig.whiteTemperatureMin;
            if (configuration.whiteTemperatureMin == null) {
                String valStr = getThing().getProperties().get(PROPERTY_MINIMUM_WHITE_TEMPERATURE);
                if (valStr != null) {
                    configuration.whiteTemperatureMin = Integer.parseInt(valStr);
                }
            }
        }

        if (configuration.whiteTemperatureMax == null) {
            configuration.whiteTemperatureMax = bridgeConfig.whiteTemperatureMax;
            if (configuration.whiteTemperatureMax == null) {
                String valStr = getThing().getProperties().get(PROPERTY_MAXIMUM_WHITE_TEMPERATURE);
                if (valStr != null) {
                    configuration.whiteTemperatureMax = Integer.parseInt(valStr);
                }
            }
        }

        if (configuration.whiteTemperatureMin != null && configuration.whiteTemperatureMax != null) {
            whiteTemperatureFactor = 100.0 / (configuration.whiteTemperatureMax - configuration.whiteTemperatureMin);
        } else {
            whiteTemperatureFactor = 100.0 / (6500.0 - 1800.0);
        }
    }

    public boolean setOnline(LightifyBridgeHandler bridgeHandler) {
        Thing thing = getThing();

        // If we need to probe we'll do that now and leave the device offline. The next
        // time we see status for it we will have the probed data and can set it online.
        // Note that this is called by LightifyDeviceState which is called by the receive
        // handler in LightifyListPairedDevices which is called from the connector thread
        // so all the probe message we queue here form an atomic block with respect to
        // the state polling.
        if (!thing.getThingTypeUID().equals(THING_TYPE_LIGHTIFY_GROUP)
        && thing.getProperties().get(PROPERTY_MINIMUM_WHITE_TEMPERATURE) == null) {
            bridgeHandler.sendMessage(new LightifySetLuminanceMessage(this, new PercentType(1)));

            bridgeHandler.sendMessage(new LightifySetTemperatureMessage(this, new DecimalType(0)));
            bridgeHandler.sendMessage(new LightifyGetProbedTemperatureMessage(this, PROPERTY_MINIMUM_WHITE_TEMPERATURE));

            bridgeHandler.sendMessage(new LightifySetTemperatureMessage(this, new DecimalType(65535)));
            bridgeHandler.sendMessage(new LightifyGetProbedTemperatureMessage(this, PROPERTY_MAXIMUM_WHITE_TEMPERATURE));

            // Re-poll to bring the device online again A.S.A.P. In fact, what we are
            // really saying is, bring the device online once all the above completes.
            bridgeHandler.sendMessage(new LightifyListPairedDevicesMessage());

            return false;

        } else {
            // If we have a valid state it's either because we saw a transient and intended
            // outage (e.g. firmware update) and want to restore the device to the state it
            // was in a few moments ago or because we messed the device up by doing some
            // probes and now need to restore the initial state.
            if (lightifyDeviceState.isSaved()) {
                // If we did probes we need to recheck what config values we should be using.
                thingUpdated(thing);

                // It may seem like we don't need to do both colour and temperature in each case
                // as changing mode is done by simply writing the new value. However if we do
                // not set both then the next poll will find a difference in state between what
                // the device has and what we know it should have. That will cause us to become
                // confused about what mode it is in and will generate bogus state updates on
                // linked items.
                if (lightifyDeviceState.getWhiteMode()) {
                    bridgeHandler.sendMessage(new LightifySetColorMessage(this, lightifyDeviceState.getRGBA()));
                    bridgeHandler.sendMessage(new LightifySetTemperatureMessage(this, lightifyDeviceState.getTemperature()));
                } else {
                    bridgeHandler.sendMessage(new LightifySetTemperatureMessage(this, lightifyDeviceState.getTemperature()));
                    bridgeHandler.sendMessage(new LightifySetColorMessage(this, lightifyDeviceState.getRGBA()));
                }

                bridgeHandler.sendMessage(new LightifySetLuminanceMessage(this, lightifyDeviceState.getLuminance()));
                bridgeHandler.sendMessage(new LightifySetSwitchMessage(this, lightifyDeviceState.getPower(), lightifyDeviceState));
            }

            updateStatus(ThingStatus.ONLINE);
            return true;
        }
    }

    public void setStatus(ThingStatus status) {
        updateStatus(status, ThingStatusDetail.NONE, null);
    }

    public void setStatus(ThingStatus status, ThingStatusDetail detail) {
        updateStatus(status, detail, null);
    }

    public void setStatus(ThingStatus status, ThingStatusDetail detail, String info) {
        updateStatus(status, detail, info);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // If the thing is not online then there is no point passing the command on
        // to the gateway. At best the gateway will just discard it, at worst the
        // gateway's send queue will be clogged until the command times out (that
        // could be as much as 7.680s on the ZigBee side as per the ZLL spec).
        if (getThing().getStatus() != ThingStatus.ONLINE) {
            return;
        }

        if (command instanceof RefreshType) {
            // Ignored.

        } else {
            logger.debug("{}, Command: {} {}", channelUID, command.getClass().getSimpleName(), command);

            LightifyBridgeHandler bridgeHandler = (LightifyBridgeHandler) getBridge().getHandler();

            if (command instanceof HSBType) {
                HSBType hsb = (HSBType) command;

                PercentType luminance = hsb.getBrightness();

                if (lightifyDeviceState.getLuminance().intValue() != luminance.intValue()) {
                    logger.debug("{}: set luminance: {}", channelUID, luminance);

                    bridgeHandler.sendMessage(
                        new LightifySetLuminanceMessage(this, luminance)
                            .setTransitionEndNanos(System.nanoTime() + (long) (TimeUnit.SECONDS.toNanos(1) * (luminance.intValue() != 0 ? configuration.transitionTime : configuration.transitionToOffTime)))
                    );

                    if (configuration.debugTransitions) {
                        bridgeHandler.sendMessage(new LightifyGetDeviceInfoMessage(this));
                    }
                }

                hsb = new HSBType(hsb.getHue(), hsb.getSaturation(), new PercentType(100));

                logger.debug("{}: set HSB: {}", channelUID, hsb);

                bridgeHandler.sendMessage(
                    new LightifySetColorMessage(this, hsb)
                        .setTransitionEndNanos(System.nanoTime() + (long) (TimeUnit.SECONDS.toNanos(1) * (hsb.getBrightness().intValue() != 0 ? configuration.transitionTime : configuration.transitionToOffTime)))
                );

                if (configuration.debugTransitions) {
                    bridgeHandler.sendMessage(new LightifyGetDeviceInfoMessage(this));
                }

            } else if (command instanceof PercentType) {
                if (channelUID.getId().equals(CHANNEL_TEMPERATURE)) {
                    // Everything else uses dimmers for white temperature so we have to too :-(
                    DecimalType temperature = new DecimalType(
                        configuration.whiteTemperatureMin
                        + (
                            ((PercentType) command).doubleValue()
                            * (configuration.whiteTemperatureMax - configuration.whiteTemperatureMin)
                          ) / 100.0
                    );

                    logger.debug("{}: set temperature: {}", channelUID, temperature);

                    bridgeHandler.sendMessage(
                        new LightifySetTemperatureMessage(this, temperature)
                            .setTransitionEndNanos(System.nanoTime() + (long) (TimeUnit.SECONDS.toNanos(1) * configuration.transitionTime))
                    );

                    if (configuration.debugTransitions) {
                        bridgeHandler.sendMessage(new LightifyGetDeviceInfoMessage(this));
                    }

                    // A command on the percent temperature channel generates a matching command
                    // on the absolute temperature channel.
                    postCommand(CHANNEL_ABS_TEMPERATURE, temperature);

                } else {
                    // It can only be luminance. It doesn't matter whether it is on the color
                    // or luminance channel. It's ALWAYS luminance.
                    PercentType luminance = (PercentType) command;

                    logger.debug("{}: set luminance: {}", channelUID, luminance);

                    bridgeHandler.sendMessage(
                        new LightifySetLuminanceMessage(this, luminance)
                            .setTransitionEndNanos(System.nanoTime() + (long) (TimeUnit.SECONDS.toNanos(1) * (luminance.intValue() != 0 ? configuration.transitionTime : configuration.transitionToOffTime)))
                    );

                    if (configuration.debugTransitions) {
                        bridgeHandler.sendMessage(new LightifyGetDeviceInfoMessage(this));
                    }
                }

            } else if (command instanceof DecimalType) {
                DecimalType temperature = (DecimalType) command;

                logger.debug("{}: set temperature: {}", channelUID, temperature);

                bridgeHandler.sendMessage(
                    new LightifySetTemperatureMessage(this, temperature)
                        .setTransitionEndNanos(System.nanoTime() + (long) (TimeUnit.SECONDS.toNanos(1) * configuration.transitionTime))
                );

                if (configuration.debugTransitions) {
                    bridgeHandler.sendMessage(new LightifyGetDeviceInfoMessage(this));
                }

                // A command on the absolute temperature channel generates a matching command
                // on the percent temperature channel.
                postCommand(CHANNEL_TEMPERATURE, temperatureToPercent(bridgeHandler, temperature));

            } else if (command instanceof OnOffType) {
                OnOffType onoff = (OnOffType) command;

                logger.debug("{}: set power: {}", channelUID, onoff);

                bridgeHandler.sendMessage(new LightifySetSwitchMessage(this, onoff, lightifyDeviceState));

            } else {
                logger.error("Handling not implemented for: {}", command);
            }
        }
    }

    public void changedPower(OnOffType onOff) {
        logger.debug("{}: update: power {}", getThing().getUID(), onOff);

        updateState(CHANNEL_SWITCH, onOff);
        updateState(CHANNEL_DIMMER, onOff);
        updateState(CHANNEL_COLOR, onOff);
    }

    public void changedLuminance(PercentType luminance) {
        logger.debug("{}: update: luminance {}", getThing().getUID(), luminance);

        updateState(CHANNEL_DIMMER, luminance);
    }

    public void changedTemperature(LightifyBridgeHandler bridgeHandler, DecimalType temperature) {
        logger.debug("{}: update: temperature {}", getThing().getUID(), temperature);

        updateState(CHANNEL_ABS_TEMPERATURE, temperature);
        updateState(CHANNEL_TEMPERATURE, temperatureToPercent(bridgeHandler, temperature));
    }

    public void changedColor(HSBType color) {
        logger.debug("{}: update: colour {}", getThing().getUID(), color);

        updateState(CHANNEL_COLOR, color);
    }

    private PercentType temperatureToPercent(LightifyBridgeHandler bridgeHandler, DecimalType temperature) {
        if (temperature.doubleValue() <= configuration.whiteTemperatureMin) {
            return new PercentType(0);
        } else if (temperature.doubleValue() >= configuration.whiteTemperatureMax) {
            return new PercentType(100);
        } else {
            double percent = whiteTemperatureFactor * (temperature.doubleValue() - configuration.whiteTemperatureMin);

            if (percent < 0) {
                percent = 0;
            } else if (percent > 100) {
                percent = 100;
            }

            return new PercentType((int) percent);
        }
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        // If the bridge goes offline we go offline too. We only go online when the
        // (or a) bridge comes online AND sees us in a list paired response.
        if (bridgeStatusInfo.getStatus() == ThingStatus.OFFLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    public String getDeviceAddress() {
        return deviceAddress;
    }

    public LightifyDeviceState getLightifyDeviceState() {
        return lightifyDeviceState;
    }

    public LightifyDeviceConfiguration getConfiguration() {
        return configuration;
    }

    public void setMinimumWhiteTemperature(int temperature) {
        thing.setProperty(PROPERTY_MINIMUM_WHITE_TEMPERATURE, Integer.toString(temperature));

        if (configuration.whiteTemperatureMin == null) {
            configuration.whiteTemperatureMin = temperature;
        }
    }

    public void setMaximumWhiteTemperature(int temperature) {
        thing.setProperty(PROPERTY_MAXIMUM_WHITE_TEMPERATURE, Integer.toString(temperature));

        if (configuration.whiteTemperatureMax == null) {
            configuration.whiteTemperatureMax = temperature;
        }
    }
}
