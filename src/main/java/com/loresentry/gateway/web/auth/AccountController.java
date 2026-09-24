package com.loresentry.gateway.web.auth;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.loresentry.gateway.application.AccountService;
import com.loresentry.gateway.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountController {
    public record Update(@JsonProperty("display_name") String displayName) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Account(UUID id,@JsonProperty("display_name") String displayName,String email) {}
    private final AccountService accounts;
    public AccountController(AccountService accounts){this.accounts=accounts;}
    @GetMapping("/auth/users/me")
    public ResponseEntity<Account> get(@CurrentUser UUID user) {return response(accounts.get(user));}
    @PatchMapping("/auth/users/me")
    public ResponseEntity<Account> update(@CurrentUser UUID user,@RequestBody Update input) {return response(accounts.update(user,input.displayName()));}
    private ResponseEntity<Account> response(AccountService.Account account) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(new Account(account.id(),account.displayName(),account.email()));
    }
}
