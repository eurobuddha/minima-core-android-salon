package com.eurobuddha.salon;

import com.goterl.lazysodium.LazySodium;
import com.goterl.lazysodium.LazySodiumAndroid;
import com.goterl.lazysodium.SodiumAndroid;

/** Single libsodium instance for the app. Ported from support/freezepeach/.../comms/Sodium.java. */
final class Sodium {
    private static LazySodium INSTANCE;

    static synchronized LazySodium get() {
        if (INSTANCE == null) INSTANCE = new LazySodiumAndroid(new SodiumAndroid());
        return INSTANCE;
    }

    private Sodium() {}
}
