package com.greenowl.callisto.service;

import com.greenowl.callisto.config.Constants;
import com.greenowl.callisto.domain.ParkingPlan;
import com.greenowl.callisto.domain.PlanEligibleUser;
import com.greenowl.callisto.repository.ParkingPlanRepository;
import com.greenowl.callisto.repository.PlanEligibleUserRepository;
import com.stripe.Stripe;
import com.stripe.exception.*;
import com.stripe.model.Customer;
import com.stripe.model.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


@SuppressWarnings("SpringJavaAutowiringInspection")
@Service
public class EligiblePlanUserService {
    private final Logger LOG = LoggerFactory.getLogger(EligiblePlanUserService.class);

    @Inject
    private ParkingPlanRepository parkingPlanRepository;

    @Inject
    private PlanEligibleUserRepository planEligibleUserRepository;

    @Inject
    private UserService userService;

    private boolean userIsEligible(String userEmail, Long planId) {
        Optional<PlanEligibleUser> optionalPlan = planEligibleUserRepository.findOneByUserEmail(userEmail);
        if (optionalPlan.isPresent()) {
            PlanEligibleUser existingPlan = optionalPlan.get();
            return (existingPlan.getId().equals(planId));
        }
        return false;
    }

    public List<PlanEligibleUser> getPlansByUserEmail(String userEmail) {
        return planEligibleUserRepository.getEligibleUsersByUserEmail(userEmail);
    }

    public String subscribePlan(String userEmail, Long planId) {
        String userToken = userService.getUser(userEmail).getStripeToken();
        ParkingPlan parkingPlan = parkingPlanRepository.getOneParkingPlanById(planId);
        if (userIsEligible(userEmail, parkingPlan.getId())) {
            return "Already Subscribed";
        } else {
            Stripe.apiKey = Constants.STRIPE_TEST_KEY;
            Customer cu;
            try {
                cu = Customer.retrieve(userToken);
            } catch (AuthenticationException | InvalidRequestException | APIConnectionException | CardException
                    | APIException e) {
                return "Failed at retrieving customer information";
            }
            Map<String, Object> params = new HashMap<>();
            params.put("plan", planId);
            try {
                Subscription subscription = cu.createSubscription(params);
                LOG.debug("Subscribed to the stripe");
                List<PlanEligibleUser> users = planEligibleUserRepository.getEligibleUsersByUserEmail(userEmail);
                for (PlanEligibleUser user : users) {
                    if (user.getPlanGroup().getId().equals(planId)) {
                        user.setSubscribed(true);
                        planEligibleUserRepository.save(user);
                        return subscription.getId();
                    }
                }
                LOG.error("Failed at editing eligible user table");
            } catch (AuthenticationException | InvalidRequestException | APIConnectionException | CardException
                    | APIException e) {
                // TODO Auto-generated catch block
                return "Subscribe Failed";
            }
            return "Failed unexpected";
        }
    }
}
