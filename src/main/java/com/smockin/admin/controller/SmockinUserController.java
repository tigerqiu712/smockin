package com.smockin.admin.controller;

import com.smockin.admin.dto.PasswordDTO;
import com.smockin.admin.dto.SmockinNewUserDTO;
import com.smockin.admin.dto.SmockinUserDTO;
import com.smockin.admin.dto.response.SimpleMessageResponseDTO;
import com.smockin.admin.dto.response.SmockinUserResponseDTO;
import com.smockin.admin.enums.UserModeEnum;
import com.smockin.admin.exception.AuthException;
import com.smockin.admin.exception.RecordNotFoundException;
import com.smockin.admin.exception.ValidationException;
import com.smockin.admin.service.SmockinUserService;
import com.smockin.utils.GeneralUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Created by mgallina.
 */
@Controller
public class SmockinUserController {

    @Autowired
    private SmockinUserService smockinUserService;

    @RequestMapping(path="/user", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<List<SmockinUserResponseDTO>> getUsers(@RequestHeader(GeneralUtils.OAUTH_HEADER_NAME) final String bearerToken)
                                                                throws RecordNotFoundException, AuthException {

        return ResponseEntity.ok(smockinUserService.loadAllUsers(GeneralUtils.extractOAuthToken(bearerToken)));
    }

    @RequestMapping(path="/user", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<String> createUser(@RequestBody final SmockinNewUserDTO dto,
                                                           @RequestHeader(GeneralUtils.OAUTH_HEADER_NAME) final String bearerToken)
                                                                throws ValidationException, RecordNotFoundException, AuthException {

        smockinUserService.createUser(dto, GeneralUtils.extractOAuthToken(bearerToken));

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @RequestMapping(path="/user/{extId}", method = RequestMethod.PUT, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<String> updateUser(@PathVariable("extId") final String extId,
                                                           @RequestBody final SmockinUserDTO dto,
                                                           @RequestHeader(GeneralUtils.OAUTH_HEADER_NAME) final String bearerToken)
                                                                throws ValidationException, RecordNotFoundException, AuthException {

        smockinUserService.updateUser(extId, dto, GeneralUtils.extractOAuthToken(bearerToken));

        return ResponseEntity.noContent().build();
    }

    @RequestMapping(path="/user/{extId}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<String> deleteUser(@PathVariable("extId") final String extId,
                                                           @RequestHeader(GeneralUtils.OAUTH_HEADER_NAME) final String bearerToken)
                                                                throws ValidationException, RecordNotFoundException, AuthException {

        smockinUserService.deleteUser(extId, GeneralUtils.extractOAuthToken(bearerToken));

        return ResponseEntity.noContent().build();
    }

    @RequestMapping(path="/user/password", method = RequestMethod.PATCH, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<String> resetUserPassword(@RequestBody final PasswordDTO dto,
                                                                  @RequestHeader(GeneralUtils.OAUTH_HEADER_NAME) final String bearerToken)
                                                                        throws ValidationException, RecordNotFoundException, AuthException {

        smockinUserService.resetPassword(dto, GeneralUtils.extractOAuthToken(bearerToken));

        return ResponseEntity.noContent().build();
    }

    @RequestMapping(path="/user/mode", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<SimpleMessageResponseDTO> getUserMode() {
        return ResponseEntity.ok(new SimpleMessageResponseDTO(smockinUserService.getUserMode()));
    }

}
