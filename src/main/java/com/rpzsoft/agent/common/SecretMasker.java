package com.rpzsoft.agent.common;

/**
 * Masks secret values before text is returned to the model or a client.
 */
public interface SecretMasker {

    String mask(String text);

    SecretMasker NOOP = new SecretMasker() {
        @Override
        public String mask(String text) {
            return text;
        }
    };
}
