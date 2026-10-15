package com.acme.demo;

import org.apache.commons.text.StringSubstitutor;

import java.util.Map;

public class Main {
    public static void main(String[] args) {
        StringSubstitutor sub = new StringSubstitutor(Map.of("name", "ShadeLedger"));
        System.out.println(sub.replace("hello ${name}"));
    }
}
