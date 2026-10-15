package com.acme.demo;

import org.apache.logging.log4j.core.LoggerContext;

public class Main {
    public static void main(String[] args) {
        LoggerContext context = LoggerContext.getContext(false);
        System.out.println("log4j-core context: " + context.getName());
    }
}
