package com.kashi.grc.vendor.service;

import com.kashi.grc.vendor.dto.request.VendorOnboardRequest;
import com.kashi.grc.vendor.dto.response.VendorOnboardResponse;

public interface VendorService {
    VendorOnboardResponse onboard(VendorOnboardRequest req);
}
