package com.easypan.entity.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserSpaceDto implements Serializable {

    private Long useSpace;

    private Long totalSpace;

}
