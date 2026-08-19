package com.digitalpartner.houseagent.property.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Where the house is. Latitude and longitude are plain columns for now; when radius
 * search arrives they become a PostGIS geography column with a GiST index, which is
 * an additive migration rather than a rewrite.
 */
@Embeddable
public class Address {

    @NotBlank
    @Size(max = 200)
    @Column(name = "street", nullable = false, length = 200)
    public String street;

    /** Neighbourhood or commune. The unit renters actually search by. */
    @Size(max = 120)
    @Column(name = "district", length = 120)
    public String district;

    @NotBlank
    @Size(max = 120)
    @Column(name = "city", nullable = false, length = 120)
    public String city;

    @NotBlank
    @Size(max = 2)
    @Column(name = "country_code", nullable = false, length = 2)
    public String countryCode;

    @Column(name = "latitude")
    public Double latitude;

    @Column(name = "longitude")
    public Double longitude;

    public Address() {
    }

    public Address(String street, String district, String city, String countryCode) {
        this.street = street;
        this.district = district;
        this.city = city;
        this.countryCode = countryCode;
    }
}
