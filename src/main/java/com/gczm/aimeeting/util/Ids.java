package com.gczm.aimeeting.util;

import java.util.UUID;

public final class Ids {

    private Ids() {
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }
}
