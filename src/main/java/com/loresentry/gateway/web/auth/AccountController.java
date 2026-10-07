package com.loresentry.gateway.web.auth;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.loresentry.gateway.application.AccountDeletionService;
import com.loresentry.gateway.application.AccountService;
import com.loresentry.gateway.application.AuthOperationFailure;
import com.loresentry.gateway.client.SupportedLocale;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.loresentry.gateway.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountController {
    public record Update(@JsonProperty("display_name") String displayName) {}
    public record LocaleUpdate(String locale) {}
    public record Deletion(@JsonProperty("confirmation_email") String confirmationEmail) {
        @Override public String toString(){return "Deletion[redacted]";}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Account(UUID id,@JsonProperty("display_name") String displayName,String email,
            @JsonProperty("onboarding_completed") boolean onboardingCompleted,String locale) {}
    private final AccountService accounts;
    private final AccountDeletionService deletions;
    private final AuthCookies cookies;
    public AccountController(AccountService accounts,AccountDeletionService deletions,AuthCookies cookies) {
        this.accounts=accounts;this.deletions=deletions;this.cookies=cookies;
    }
    @GetMapping("/auth/users/me")
    public ResponseEntity<Account> get(@CurrentUser UUID user) {return response(accounts.get(user));}
    @PatchMapping("/auth/users/me")
    public ResponseEntity<Account> update(@CurrentUser UUID user,@RequestBody Update input) {return response(accounts.update(user,input.displayName()));}
    @PutMapping("/auth/users/me/locale")
    public ResponseEntity<Account> updateLocale(@CurrentUser UUID user,@RequestBody LocaleUpdate input) {
        if(input==null||!SupportedLocale.valid(input.locale())) throw new AuthOperationFailure(400,"INVALID_REQUEST","NONE");
        return response(accounts.updateLocale(user,input.locale()));
    }
    @PutMapping("/auth/users/me/onboarding")
    public ResponseEntity<Void> completeOnboarding(@CurrentUser UUID user) {
        accounts.completeOnboarding(user);
        return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
    @PostMapping("/auth/users/me/deletion")
    public ResponseEntity<Void> delete(@CurrentUser UUID user,@RequestBody Deletion input,HttpServletRequest request,HttpServletResponse response) {
        if(input==null||input.confirmationEmail()==null) throw new AuthOperationFailure(400,"INVALID_REQUEST","NONE");
        deletions.delete(user,input.confirmationEmail());
        var legacy=SessionCookieResponse.end(response).orElseGet(()->cookies.clearLegacy(request));
        var result=ResponseEntity.noContent().header("Cache-Control","no-store").header("Set-Cookie",cookies.clearSession().toString());
        legacy.forEach(cookie->result.header("Set-Cookie",cookie.toString()));
        return result.build();
    }
    private ResponseEntity<Account> response(AccountService.Account account) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(new Account(account.id(),account.displayName(),account.email(),account.onboardingCompleted(),account.locale()));
    }
}
