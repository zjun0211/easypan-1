package com.easypan.entity.dto;

import lombok.Data;

@Data
public class SessionWebUserDto {
    private String nickName;
    private String userId;
    private Boolean isAdmin;
    private Long useSpace;
    private Long totalSpace;
    private String avatar;
}
