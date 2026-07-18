package com.wekaapi.controller;

import com.wekaapi.dto.AssociateRequest;
import com.wekaapi.error.ApiException;
import com.wekaapi.service.AssociationService;
import io.javalin.http.Context;
import weka.associations.Associator;
import weka.core.ClassDiscovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class AssociatorController {

    private final AssociationService service;
    private List<String> cached;

    public AssociatorController(AssociationService service) {
        this.service = service;
    }

    public synchronized void discover(Context ctx) {
        if (cached == null) {
            LinkedHashSet<String> classes = new LinkedHashSet<>();
            try {
                List<String> found = ClassDiscovery.find(Associator.class.getName(), "weka.associations");
                if (found != null) classes.addAll(found);
            } catch (Throwable ignored) {}
            for (String fqn : List.of(
                    "weka.associations.Apriori",
                    "weka.associations.FPGrowth",
                    "weka.associations.FilteredAssociator")) {
                try {
                    Class.forName(fqn);
                    classes.add(fqn);
                } catch (ClassNotFoundException ignored) {}
            }
            List<String> sorted = new ArrayList<>(classes);
            Collections.sort(sorted);
            cached = sorted;
        }
        ctx.json(Map.of("associators", cached));
    }

    public void associate(Context ctx) {
        AssociateRequest req;
        try {
            req = ctx.bodyAsClass(AssociateRequest.class);
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "invalid JSON: " + e.getMessage(), e);
        }
        ctx.json(service.associate(req));
    }
}
