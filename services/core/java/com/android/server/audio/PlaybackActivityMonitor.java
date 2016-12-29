/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.audio;

import android.annotation.NonNull;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioSystem;
import android.media.IPlaybackConfigDispatcher;
import android.media.MediaRecorder;
import android.media.PlayerBase;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * Class to receive and dispatch updates from AudioSystem about recording configurations.
 */
public final class PlaybackActivityMonitor {

    public final static String TAG = "AudioService.PlaybackActivityMonitor";
    private final static boolean DEBUG = false;

    private ArrayList<PlayMonitorClient> mClients = new ArrayList<PlayMonitorClient>();
    // a public client is one that needs an anonymized version of the playback configurations, we
    // keep track of whether there is at least one to know when we need to create the list of
    // playback configurations that do not contain uid/pid/package name information.
    private boolean mHasPublicClients = false;

    private final Object mPlayerLock = new Object();
    private HashMap<Integer, AudioPlaybackConfiguration> mPlayers =
            new HashMap<Integer, AudioPlaybackConfiguration>();

    PlaybackActivityMonitor() {
        PlayMonitorClient.sMonitor = this;
    }

    //=================================================================
    // Track players and their states
    // methods trackPlayer, playerAttributes, playerEvent, releasePlayer are all oneway calls
    //  into AudioService. They trigger synchronous dispatchPlaybackChange() which updates
    //  all listeners as oneway calls.

    public void trackPlayer(PlayerBase.PlayerIdCard pic) {
        if (DEBUG) { Log.v(TAG, "trackPlayer() for piid=" + pic.mPIId); }
        final AudioPlaybackConfiguration apc = new AudioPlaybackConfiguration(pic);
        synchronized(mPlayerLock) {
            mPlayers.put(pic.mPIId, apc);
        }
    }

    public void playerAttributes(int piid, @NonNull AudioAttributes attr) {
        final boolean change;
        synchronized(mPlayerLock) {
            final AudioPlaybackConfiguration apc = mPlayers.get(new Integer(piid));
            if (apc == null) {
                Log.e(TAG, "Unknown player " + piid + " for audio attributes change");
                change = false;
            } else {
                change = apc.handleAudioAttributesEvent(attr);
            }
        }
        if (change) {
            dispatchPlaybackChange();
        }
    }

    public void playerEvent(int piid, int event) {
        if (DEBUG) { Log.v(TAG, String.format("trackPlayer(piid=%d, event=%d)", piid, event)); }
        final boolean change;
        synchronized(mPlayerLock) {
            final AudioPlaybackConfiguration apc = mPlayers.get(new Integer(piid));
            if (apc == null) {
                Log.e(TAG, "Unknown player " + piid + " for event " + event);
                change = false;
            } else {
                //TODO add generation counter to only update to the latest state
                change = apc.handleStateEvent(event);
            }
        }
        if (change) {
            dispatchPlaybackChange();
        }
    }

    public void releasePlayer(int piid) {
        if (DEBUG) { Log.v(TAG, "releasePlayer() for piid=" + piid); }
        synchronized(mPlayerLock) {
            if (!mPlayers.containsKey(new Integer(piid))) {
                Log.e(TAG, "Unknown player " + piid + " for release");
            } else {
                mPlayers.remove(new Integer(piid));
            }
        }
    }

    protected void dump(PrintWriter pw) {
        pw.println("\nPlaybackActivityMonitor dump time: "
                + DateFormat.getTimeInstance().format(new Date()));
        synchronized(mPlayerLock) {
            for (AudioPlaybackConfiguration conf : mPlayers.values()) {
                conf.dump(pw);
            }
        }
    }

    private void dispatchPlaybackChange() {
        synchronized (mClients) {
            // typical use case, nobody is listening, don't do any work
            if (mClients.isEmpty()) {
                return;
            }
        }
        if (DEBUG) { Log.v(TAG, "dispatchPlaybackChange to " + mClients.size() + " clients"); }
        final List<AudioPlaybackConfiguration> configsSystem;
        // list of playback configurations for "public consumption". It is only computed if there
        // are non-system playback activity listeners.
        final List<AudioPlaybackConfiguration> configsPublic;
        synchronized (mPlayerLock) {
            if (mPlayers.isEmpty()) {
                return;
            }
            configsSystem = new ArrayList<AudioPlaybackConfiguration>(mPlayers.values());
        }
        synchronized (mClients) {
            // was done at beginning of method, but could have changed
            if (mClients.isEmpty()) {
                return;
            }
            configsPublic = mHasPublicClients ? anonymizeForPublicConsumption(configsSystem) : null;
            final Iterator<PlayMonitorClient> clientIterator = mClients.iterator();
            while (clientIterator.hasNext()) {
                final PlayMonitorClient pmc = clientIterator.next();
                try {
                    // do not spam the logs if there are problems communicating with this client
                    if (pmc.mErrorCount < PlayMonitorClient.MAX_ERRORS) {
                        if (pmc.mIsPrivileged) {
                            pmc.mDispatcherCb.dispatchPlaybackConfigChange(configsSystem);
                        } else {
                            pmc.mDispatcherCb.dispatchPlaybackConfigChange(configsPublic);
                        }
                    }
                } catch (RemoteException e) {
                    pmc.mErrorCount++;
                    Log.e(TAG, "Error (" + pmc.mErrorCount +
                            ") trying to dispatch playback config change to " + pmc, e);
                }
            }
        }
    }

    private ArrayList<AudioPlaybackConfiguration> anonymizeForPublicConsumption(
            List<AudioPlaybackConfiguration> sysConfigs) {
        ArrayList<AudioPlaybackConfiguration> publicConfigs =
                new ArrayList<AudioPlaybackConfiguration>();
        // only add active anonymized configurations, 
        for (AudioPlaybackConfiguration config : sysConfigs) {
            if (config.isActive()) {
                publicConfigs.add(AudioPlaybackConfiguration.anonymizedCopy(config));
            }
        }
        return publicConfigs;
    }

    //=================================================================
    // Track playback activity listeners

    void registerPlaybackCallback(IPlaybackConfigDispatcher pcdb, boolean isPrivileged) {
        if (pcdb == null) {
            return;
        }
        synchronized(mClients) {
            final PlayMonitorClient pmc = new PlayMonitorClient(pcdb, isPrivileged);
            if (pmc.init()) {
                if (!isPrivileged) {
                    mHasPublicClients = true;
                }
                mClients.add(pmc);
            }
        }
    }

    void unregisterPlaybackCallback(IPlaybackConfigDispatcher pcdb) {
        if (pcdb == null) {
            return;
        }
        synchronized(mClients) {
            final Iterator<PlayMonitorClient> clientIterator = mClients.iterator();
            boolean hasPublicClients = false;
            // iterate over the clients to remove the dispatcher to remove, and reevaluate at
            // the same time if we still have a public client.
            while (clientIterator.hasNext()) {
                PlayMonitorClient pmc = clientIterator.next();
                if (pcdb.equals(pmc.mDispatcherCb)) {
                    pmc.release();
                    clientIterator.remove();
                } else {
                    if (!pmc.mIsPrivileged) {
                        hasPublicClients = true;
                    }
                }
            }
            mHasPublicClients = hasPublicClients;
        }
    }

    List<AudioPlaybackConfiguration> getActivePlaybackConfigurations() {
        synchronized(mPlayers) {
            return new ArrayList<AudioPlaybackConfiguration>(mPlayers.values());
        }
    }


    /**
     * Inner class to track clients that want to be notified of playback updates
     */
    private final static class PlayMonitorClient implements IBinder.DeathRecipient {

        // can afford to be static because only one PlaybackActivityMonitor ever instantiated
        static PlaybackActivityMonitor sMonitor;

        final IPlaybackConfigDispatcher mDispatcherCb;
        final boolean mIsPrivileged;

        int mErrorCount = 0;
        // number of errors after which we don't update this client anymore to not spam the logs
        static final int MAX_ERRORS = 5;

        PlayMonitorClient(IPlaybackConfigDispatcher pcdb, boolean isPrivileged) {
            mDispatcherCb = pcdb;
            mIsPrivileged = isPrivileged;
        }

        public void binderDied() {
            Log.w(TAG, "client died");
            sMonitor.unregisterPlaybackCallback(mDispatcherCb);
        }

        boolean init() {
            try {
                mDispatcherCb.asBinder().linkToDeath(this, 0);
                return true;
            } catch (RemoteException e) {
                Log.w(TAG, "Could not link to client death", e);
                return false;
            }
        }

        void release() {
            mDispatcherCb.asBinder().unlinkToDeath(this, 0);
        }
    }
}