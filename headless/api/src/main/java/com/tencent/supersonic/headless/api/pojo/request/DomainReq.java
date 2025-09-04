package com.tencent.supersonic.headless.api.pojo.request;

import com.tencent.supersonic.headless.api.pojo.SchemaItem;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DomainReq extends SchemaItem {

    private Long parentId = 0L;

    private Integer isOpen = 0;

    private List<String> viewers = new ArrayList<>();

    private List<String> viewOrgs = new ArrayList<>();

    private List<String> admins = new ArrayList<>();

    private List<String> adminOrgs = new ArrayList<>();

    public String getViewers() {
        if (viewers == null) {
            return null;
        }
        return String.join(",", viewers);
    }

    public String getViewOrgs() {
        if (viewOrgs == null) {
            return null;
        }
        return String.join(",", viewOrgs);
    }

    public String getAdmins() {
        if (admins == null) {
            return null;
        }
        return String.join(",", admins);
    }

    public String getAdminOrgs() {
        if (adminOrgs == null) {
            return null;
        }
        return String.join(",", adminOrgs);
    }
}
