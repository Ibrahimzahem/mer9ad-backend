package com.hackathon.ra9edhamad.api;

import com.hackathon.ra9edhamad.domain.AccountStore;
import com.hackathon.ra9edhamad.domain.CustomerProfile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the static demo customer the frontend renders: profile, balance, saved payees and the
 * transaction history. The backend is the single source of truth for this data.
 */
@RestController
@RequestMapping("/api")
public class CustomerController {

    private final AccountStore accountStore;

    public CustomerController(AccountStore accountStore) {
        this.accountStore = accountStore;
    }

    /** Convenience: the default demo customer (cust_8842). */
    @GetMapping("/customer")
    public CustomerView defaultCustomer() {
        return CustomerView.from(accountStore.demoCustomer());
    }

    @GetMapping("/customer/{customerRef}")
    public ResponseEntity<CustomerView> customer(@PathVariable String customerRef) {
        CustomerProfile profile = accountStore.customer(customerRef);
        if (profile == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(CustomerView.from(profile));
    }
}
