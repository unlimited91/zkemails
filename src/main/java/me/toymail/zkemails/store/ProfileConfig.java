package me.toymail.zkemails.store;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

public final class ProfileConfig {
    public List<String> profiles = new ArrayList<>();

    @JsonProperty("default")
    public String defaultProfile;
}
