package com.greenowl.callisto.web.rest;

import com.codahale.metrics.annotation.Timed;
import com.greenowl.callisto.config.AppConfigKey;
import com.greenowl.callisto.config.ErrorCodeConstants;
import com.greenowl.callisto.domain.*;
import com.greenowl.callisto.repository.UserRepository;
import com.greenowl.callisto.security.AuthoritiesConstants;
import com.greenowl.callisto.security.SecurityUtils;
import com.greenowl.callisto.service.*;
import com.greenowl.callisto.service.config.ConfigService;
import com.greenowl.callisto.service.register.RegistrationService;
import com.greenowl.callisto.service.util.UserUtil;
import com.greenowl.callisto.util.ParkingActivityUtil;
import com.greenowl.callisto.web.rest.dto.ParkingActivityDTO;
import com.greenowl.callisto.web.rest.dto.ParkingPlanDTO;
import com.greenowl.callisto.web.rest.dto.PasswordUpdateDTO;
import com.greenowl.callisto.web.rest.dto.UserDTO;
import com.greenowl.callisto.web.rest.dto.user.CreateUserRequest;
import com.greenowl.callisto.web.rest.dto.user.UpdateAccountRequest;
import com.greenowl.callisto.web.rest.parking.LogRequest;

import io.swagger.annotations.ApiOperation;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.greenowl.callisto.exception.ErrorResponseFactory.genericBadReq;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.OK;

/**
 * REST controller for managing the current user's account.
 */
@RestController
@RequestMapping("/api/{apiVersion}/")
public class AccountResource {

    private final Logger LOG = LoggerFactory.getLogger(AccountResource.class);

    private static final String USERNAME_TAKEN = "username is already in use!";

    private static final String PHONE_NUM_TAKEN = "mobile phone number is already in use!";

    private static final String STRIPE_FAILED = "register with stripe failed!";

    private static final String PLAN_NOT_FOUND = "Unable to find suitable plan.";

    @Inject
    private UserRepository userRepository;

    @Inject
    private RegistrationService registrationService;

    @Inject
    private UserService userService;

    @Inject
    private EligiblePlanUserService eligiblePlanUserService;

    @Inject
    private ParkingPlanService parkingPlanService;

    @Inject
    private SubscriptionService subscriptionService;

    @Inject
    private ParkingActivityService parkingActivityService;

    @Inject
    private ConfigService configService;
    
    @Inject
    private ExceptionLogService exceptionLogService;

    /**
     * POST /register -> register the user while adding a stripe token and
     * return parking plans the user can subscribe.
     */
    @RequestMapping(value = "/register", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    @Transactional(readOnly = false)
    @ApiOperation(notes = "Open api for allowing users to register for a new account. No authentication token is required.", value = "registerAccount")
    public ResponseEntity<?> registerAccount(@PathVariable("apiVersion") final String apiVersion,
                                             @Valid @RequestBody CreateUserRequest req) {
        Optional<User> optional = userRepository.findOneByLogin(req.getEmail()); // login
        // available
        if (optional.isPresent()) {
            return new ResponseEntity<>(
                    genericBadReq(USERNAME_TAKEN, "/register", ErrorCodeConstants.REGISTER_USERNAME_TAKEN),
                    BAD_REQUEST);
        }
        Optional<User> optUSer = userRepository.findOneByMobileNumber(req.getMobileNumber());
        if (optUSer.isPresent()) {
            return new ResponseEntity<>(
                    genericBadReq(PHONE_NUM_TAKEN, "/register", ErrorCodeConstants.REGISTER_PHONENUM_TAKEN),
                    BAD_REQUEST);
        }
        // Check for plan eligibility
        List<PlanEligibleUser> users = eligiblePlanUserService.getPlansByUserEmail(req.getEmail());
        if (users.size() == 0) {
            return new ResponseEntity<>(
                    genericBadReq(PLAN_NOT_FOUND, "/register", ErrorCodeConstants.REGISTER_PLAN_NOTFOUND), BAD_REQUEST);
        }
        // Check to see if payment is enabled atm.
        Boolean stripeEnabled = configService.get(AppConfigKey.STRIPE_ENABLED.name(), Boolean.class, false);
        String stripeToken = null;
        if (stripeEnabled) {
            LOG.info("Stripe payment provider is currently enabled. Attempting to add payment info during registration flow.");
            stripeToken = registrationService.stripeRegister(req);
            if (stripeToken == null) {
                return new ResponseEntity<>(
                        genericBadReq(STRIPE_FAILED, "/register", ErrorCodeConstants.REGISTER_STRIPE_FAILED), BAD_REQUEST);
            }
        } else {
            LOG.info("Stripe is currently disabled. Not proceeding with payment info during registration flow.");
        }

        UserDTO dto = registrationService.register(req, stripeToken);

        List<ParkingPlanDTO> parkingPlanDTOs = new ArrayList<>();
        if (users.size() == 1) {
            ParkingPlanDTO parkingPlanDTO = parkingPlanService
                    .createParkingPlanInformation(users.get(0).getPlanGroup());
            if (users.get(0).getPlanGroup().getUnitChargeAmount() == 0) {
                LOG.debug("Oh yeah");
                subscriptionService.autoSubscribe(dto.getId(), parkingPlanDTO.getPlanId());
            }
            return new ResponseEntity<>(parkingPlanDTO, OK);
        }
        for (PlanEligibleUser user : users) {
            ParkingPlan plan = user.getPlanGroup();
            if (plan != null) {
                ParkingPlanDTO parkingPlanDTO = parkingPlanService.createParkingPlanInformation(plan);
                parkingPlanDTOs.add(parkingPlanDTO);
                if (user.getPlanGroup().getUnitChargeAmount() == 0) {
                    subscriptionService.autoSubscribe(dto.getId(), parkingPlanDTO.getPlanId());
                }
            }
        }
        return new ResponseEntity<>(parkingPlanDTOs, OK);
    }

    /**
     * GET /account -> get the current user.
     */
    @Timed
    @RequestMapping(value = "/account", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserDTO> getAccount(@PathVariable("apiVersion") final String apiVersion) {
        User user = userService.getUserWithAuthorities();
        if (user == null) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        List<String> roles = user.getAuthorities().stream().map(Authority::getName).collect(Collectors.toList());
        User cUser = userService.getCurrentUser();
        Optional<ParkingActivity> opt = parkingActivityService.getLatestActivityForUser(cUser);
        UserDTO userDTO;
        if (opt.isPresent()) {
            LOG.debug("Attaching latest parking activity for user with login = {}", user.getLogin());
            ParkingActivityDTO latestParkingActivity = ParkingActivityUtil.constructDTO(opt.get());
            userDTO = UserUtil.getUserDTOWithparkingStatus(user, roles, latestParkingActivity);
        } else {
            LOG.warn("Unable to find latest parking activity for user with login = {}", user.getLogin());
            userDTO = UserUtil.getUserDTO(user, roles);
        }
        LOG.debug("Returning user back to client: {}", userDTO);
        return new ResponseEntity<>(userDTO, OK);
    }

    /**
     * POST /account -> update the current user information.
     */
    @RequestMapping(value = "/account", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    @RolesAllowed(AuthoritiesConstants.USER)
    public ResponseEntity<?> saveAccount(@PathVariable("apiVersion") final String apiVersion,
                                         @RequestBody UpdateAccountRequest req) {
        userService.updateUserInformation(SecurityUtils.getCurrentLogin(), req.getFirstName(), req.getLastName(),
                req.getRegion());
        return new ResponseEntity<>(OK);
    }

    /**
     * POST /change_password -> changes the current user's password
     */
    @RequestMapping(value = "/account/change_password", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> changePassword(@PathVariable("apiVersion") final String apiVersion,
                                            @RequestBody PasswordUpdateDTO dto) {
        String password = dto.getPassword();
        if (StringUtils.isEmpty(password) || password.length() < 5 || password.length() > 50) {
            return new ResponseEntity<>(
                    genericBadReq("Password must be between 5 and 50 characters", "api/account/change_password"),
                    BAD_REQUEST);
        }
        userService.changePassword(password);
        return new ResponseEntity<>(OK);
    }

    
    /**
     *  /logMessage -> record client exception message 
     */
    @RequestMapping(value = "/logMessage", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)    
    @Transactional(readOnly = false)
    public ResponseEntity<?> saveClientExcetionLog(@PathVariable("apiVersion") final String apiVersion,
    		@RequestBody LogRequest req) {
    	LOG.info("begin to save log message:"+req.getLogEvent());
    	User user = userService.getCurrentUser();
    	try{
    	    ExceptionLog exceptionLog = new ExceptionLog();
    	    exceptionLog.setActivityHolder(user);
    	    if(req.getLogEvent()!=null){
    	    	StringBuilder events = new StringBuilder();
    	    	for(int i=Math.max(0,req.getLogEvent().size()-40);i<req.getLogEvent().size();i++){
    	    	   events.append(req.getLogEvent().get(i));
    	    	   events.append("\n");
    	    	}
    	    	exceptionLog.setLogMessage(events.toString());
    	    }    	    
    	    exceptionLogService.saveExceptionLog(exceptionLog);  
    	    return new ResponseEntity<>(org.springframework.http.HttpStatus.OK);
    	}
    	catch(Exception e){
    		LOG.error(e.getMessage(),e);
    	}
    	return new ResponseEntity<>(genericBadReq("Failed to save log message","/logMessage", -1), org.springframework.http.HttpStatus.BAD_REQUEST);
    }
    
}
