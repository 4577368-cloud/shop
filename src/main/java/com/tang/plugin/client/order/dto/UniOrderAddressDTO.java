package com.tang.plugin.client.order.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class UniOrderAddressDTO {
    private String email;
    private String firstName;
    private String lastName;
    private String name;
    private String company;
    private String phone;
    private String address1;
    private String address2;
    private String city;
    private String zip;
    private String province;
    private String country;
    private String countryCode;
}
