package com.storageplanner.v2test;

/** Simulated process death. An Error, so v2 code (which catches only Exceptions) never swallows it. */
public final class SimulatedKill extends Error {
    public SimulatedKill(String m) { super(m); }
}
