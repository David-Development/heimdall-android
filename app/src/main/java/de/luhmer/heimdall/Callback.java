package de.luhmer.heimdall;

/**
 * Created by david on 22.12.17.
 */

public interface Callback<T> {
    public void call(T arg);
}
