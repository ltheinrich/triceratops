package com.pluscubed.velociraptor.api.osm.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)

public class Tags {

    @JsonProperty("highway")
    private String highway;
    @JsonProperty("maxspeed")
    private String maxspeed;
    @JsonProperty("maxspeed:conditional")
    private String maxspeedConditional;
    @JsonProperty("name")
    private String name;
    @JsonProperty("ref")
    private String ref;

    /**
     * @return The highway
     */
    @JsonProperty("highway")
    public String getHighway() {
        return highway;
    }

    /**
     * @param highway The highway
     */
    @JsonProperty("highway")
    public void setHighway(String highway) {
        this.highway = highway;
    }

    /**
     * @return The maxspeed
     */
    @JsonProperty("maxspeed")
    public String getMaxspeed() {
        return maxspeed;
    }

    /**
     * @param maxspeed The maxspeed
     */
    @JsonProperty("maxspeed")
    public void setMaxspeed(String maxspeed) {
        this.maxspeed = maxspeed;
    }

    /**
     * @return The maxspeed conditional
     */
    @JsonProperty("maxspeed:conditional")
    public String getMaxspeedConditional() {
        return maxspeedConditional;
    }

    /**
     * @param maxspeedConditional The maxspeed conditional
     */
    @JsonProperty("maxspeed:conditional")
    public void setMaxspeedConditional(String maxspeedConditional) {
        this.maxspeedConditional = maxspeedConditional;
    }

    /**
     * @return The name
     */
    @JsonProperty("name")
    public String getName() {
        return name;
    }

    /**
     * @param name The name
     */
    @JsonProperty("name")
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return The ref
     */
    @JsonProperty("ref")
    public String getRef() {
        return ref;
    }

    /**
     * @param ref The ref
     */
    @JsonProperty("ref")
    public void setRef(String ref) {
        this.ref = ref;
    }

}
