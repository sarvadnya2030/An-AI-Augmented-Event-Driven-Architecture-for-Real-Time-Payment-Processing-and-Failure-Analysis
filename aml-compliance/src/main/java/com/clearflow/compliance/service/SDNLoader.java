package com.clearflow.compliance.service;

import com.clearflow.compliance.domain.SDNEntry;
import com.opencsv.CSVReader;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class SDNLoader {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<SDNEntry> sdnEntries = new ArrayList<>();
    private List<String> allNames = new ArrayList<>();

    @PostConstruct
    public void loadSDN() {
        refreshSDN();
    }

    @Scheduled(fixedDelay = 86400000)
    public void refreshSDN() {
        lock.writeLock().lock();
        try {
            List<SDNEntry> loaded = new ArrayList<>();
            List<String> names = new ArrayList<>();
            try (CSVReader reader = new CSVReader(new InputStreamReader(new ClassPathResource("data/sdn_sample.csv").getInputStream()))) {
                String[] row;
                reader.readNext();
                while ((row = reader.readNext()) != null) {
                    String uid = row[0];
                    String name = row[1];
                    String type = row[2];
                    List<String> programs = Arrays.asList(row[3].split("\\|"));
                    String remarks = row[4];
                    List<String> variants = new ArrayList<>();
                    if (remarks.contains("AKA:")) {
                        String aka = remarks.substring(remarks.indexOf("AKA:") + 4).trim();
                        variants.addAll(Arrays.stream(aka.split(";")).map(String::trim).toList());
                    }
                    SDNEntry entry = new SDNEntry(uid, name, type, programs, remarks, variants, "UNKNOWN");
                    loaded.add(entry);
                    names.add(name);
                    names.addAll(variants);
                }
            } catch (Exception ignored) {
            }
            this.sdnEntries = loaded;
            this.allNames = names;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<String> getAllNames() {
        lock.readLock().lock();
        try {
            return List.copyOf(allNames);
        } finally {
            lock.readLock().unlock();
        }
    }
}
