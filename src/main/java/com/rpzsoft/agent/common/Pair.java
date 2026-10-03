

package com.rpzsoft.agent.common;


import java.io.Serializable;

public class Pair<K, V> implements Serializable {
    public K key;
    public V value;

    public Pair(K key, V value) {
        this.key = key;
        this.value = value;
    }

    @Override
    public String toString() {
        return "{" +
                "key=" + key +
                ", value=" + value +
                '}';
    }
}