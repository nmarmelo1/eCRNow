package com.drajer.sof.utils;

import com.drajer.eca.model.ActionRepo;
import com.drajer.sof.model.ClientDetails;
import com.drajer.sof.model.LaunchDetails;
import com.drajer.sof.model.Response;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Component
public class RefreshTokenScheduler {

  @Autowired ThreadPoolTaskScheduler taskScheduler;

  private final Logger logger = LoggerFactory.getLogger(RefreshTokenScheduler.class);

  private static final String GRANT_TYPE = "grant_type";
  private static final String SCOPE = "scope";
  private static final String CLIENT_CREDENTIALS = "client_credentials";
  private static final String ACCEPT_HEADER = "Accept";
  private static final String ACCESS_TOKEN = "access_token";
  private static final String ACCESS_TOKEN_CAMEL_CASE = "accessToken";
  private static final String EXPIRES_IN = "expires_in";
  private static final String EXPIRES_IN_CAMEL_CASE = "expiresIn";
  private static final String PROVIDER_UUID = "uuid";

  public void scheduleJob(LaunchDetails authDetails) {
    logger.info(
        "Scheduling Cron Job to Get AccessToken which expires every {} seconds",
        authDetails.getExpiry());
    long minutes = TimeUnit.SECONDS.toMinutes(authDetails.getExpiry());
    String cronExpression = "0 " + "0/" + minutes + " * * * ?";
    CronTrigger cronTrigger = new CronTrigger(cronExpression);
    taskScheduler.schedule(new RunnableTask(authDetails), cronTrigger);
    logger.info(
        "Job Scheduled to get AccessToken for every {} minutes for Client: {}",
        minutes,
        authDetails.getClientId());
  }

  class RunnableTask implements Runnable {

    private LaunchDetails authDetails;

    public RunnableTask(LaunchDetails authDetails) {
      this.authDetails = authDetails;
    }

    @Override
    public void run() {
      try {
        getAccessTokenUsingLaunchDetails(this.authDetails);
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        logger.info("Error in Getting AccessToken=====>", e);
      }
    }
  }

  /*
   * public JSONObject getSystemAccessToken(ClientDetails clientDetails) {
   * JSONObject tokenResponse = null;
   * logger.info("Getting AccessToken for Client: " +
   * clientDetails.getClientId()); try { RestTemplate resTemplate = new
   * RestTemplate(); HttpHeaders headers = new HttpHeaders();
   * headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
   * headers.add("Accept", MediaType.APPLICATION_JSON_VALUE); String authValues =
   * clientDetails.getClientId() + ":" + clientDetails.getClientSecret(); String
   * base64EncodedString =
   * Base64.getEncoder().encodeToString(authValues.getBytes("utf-8"));
   * headers.add("Authorization", "Basic " + base64EncodedString);
   * MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
   * map.add(GRANT_TYPE, "client_credentials"); map.add("scope",
   * clientDetails.getScopes()); if (clientDetails.getRequireAud()) {
   * logger.info("Adding Aud Parameter while getting Accesstoken"); map.add("aud",
   * clientDetails.getFhirServerBaseURL()); } HttpEntity<MultiValueMap<String,
   * String>> entity = new HttpEntity<>(map, headers);
   *
   * ResponseEntity<?> response = resTemplate.exchange(
   * clientDetails.getTokenURL(), HttpMethod.POST, entity, Response.class);
   * tokenResponse = new JSONObject(response.getBody());
   * logger.info("Received AccessToken for Client: {}",
   * clientDetails.getClientId()); logger.info("Received AccessToken: {}",
   * tokenResponse);
   *
   * } catch (Exception e) { logger.error(
   * "Error in Getting the AccessToken for the client: {} ",
   * clientDetails.getClientId(), e); } return tokenResponse; }
   */
  public JSONObject getAccessTokenUsingLaunchDetails(LaunchDetails authDetails) {
    JSONObject tokenResponse = null;
    logger.trace("Getting AccessToken for Client: {}", authDetails.getClientId());
    try {
      RestTemplate resTemplate = new RestTemplate();
      HttpHeaders headers = new HttpHeaders();
      if (Boolean.FALSE.equals(authDetails.getIsSystem())
          && Boolean.FALSE.equals(authDetails.getIsUserAccountLaunch())) {
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add(GRANT_TYPE, "refresh_token");
        map.add("refresh_token", authDetails.getRefreshToken());
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(map, headers);
        ResponseEntity<?> response =
            resTemplate.exchange(
                authDetails.getTokenUrl(), HttpMethod.POST, entity, Response.class);
        tokenResponse = new JSONObject(response.getBody());
      } else if (Boolean.TRUE.equals(authDetails.getIsSystem())) {
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.add(ACCEPT_HEADER, MediaType.APPLICATION_JSON_VALUE);
        String authValues = authDetails.getClientId() + ":" + authDetails.getClientSecret();
        String base64EncodedString =
            Base64.getEncoder().encodeToString(authValues.getBytes(StandardCharsets.UTF_8));
        headers.add("Authorization", "Basic " + base64EncodedString);
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add(GRANT_TYPE, "client_credentials");
        map.add("scope", authDetails.getScope());
        if (Boolean.TRUE.equals(authDetails.getRequireAud()) && authDetails.getIsSystem()) {
          logger.debug("Adding Aud Parameter while getting AccessToken");
          map.add("aud", authDetails.getEhrServerURL());
        }
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(map, headers);
        ResponseEntity<?> response =
            resTemplate.exchange(
                authDetails.getTokenUrl(), HttpMethod.POST, entity, Response.class);
        tokenResponse = new JSONObject(response.getBody());
      } else if (Boolean.TRUE.equals(authDetails.getIsUserAccountLaunch())) {
        headers.setBasicAuth(authDetails.getClientId(), authDetails.getClientSecret());

        String tokenUrl =
            authDetails.getTokenUrl()
                + "?"
                + GRANT_TYPE
                + "="
                + CLIENT_CREDENTIALS
                + "&"
                + SCOPE
                + "="
                + authDetails.getScope();

        HttpEntity entity = new HttpEntity(headers);

        ResponseEntity<?> response =
            resTemplate.exchange(tokenUrl, HttpMethod.GET, entity, String.class);
        if (response.getStatusCode().is2xxSuccessful()) {
          String responseBody =
              response
                  .getBody()
                  .toString()
                  .replace(ACCESS_TOKEN_CAMEL_CASE, ACCESS_TOKEN)
                  .replace(EXPIRES_IN_CAMEL_CASE, EXPIRES_IN);
          tokenResponse = new JSONObject(responseBody);
        }
      }
      logger.trace("Received AccessToken for Client {}", authDetails.getClientId());
      updateAccessToken(authDetails, tokenResponse);

    } catch (Exception e) {
      logger.error(
          "Error in Getting the AccessToken for the client: {}", authDetails.getClientId(), e);
    }
    return tokenResponse;
  }

  private void updateAccessToken(LaunchDetails authDetails, JSONObject tokenResponse) {
    try {
      LaunchDetails existingAuthDetails =
          ActionRepo.getInstance().getLaunchService().getAuthDetailsById(authDetails.getId());
      if (existingAuthDetails != null) {
        logger.trace("Updating the AccessToken value in LaunchDetails table");
        existingAuthDetails.setAccessToken(tokenResponse.getString(ACCESS_TOKEN));
        existingAuthDetails.setExpiry(tokenResponse.getInt(EXPIRES_IN));
        if (tokenResponse.has(PROVIDER_UUID)) {
          existingAuthDetails.setProviderUUID(tokenResponse.getString(PROVIDER_UUID));
        }
        existingAuthDetails.setLastUpdated(new Date());
        long expiresInSec = tokenResponse.getLong(EXPIRES_IN);
        Instant expireInstantTime = new Date().toInstant().plusSeconds(expiresInSec);
        existingAuthDetails.setTokenExpiryDateTime(Date.from(expireInstantTime));
        ActionRepo.getInstance().getLaunchService().saveOrUpdate(existingAuthDetails);
        logger.trace("Successfully updated AccessToken value in LaunchDetails table");
      }
    } catch (Exception e) {
      logger.error("Error in Updating the AccessToken value into LaunchDetails: ", e);
    }
  }

  /*
   * public JSONObject getSystemAccessToken(ClientDetails clientDetails) {
   * JSONObject tokenResponse = null;
   * logger.info("Getting AccessToken for Client: " +
   * clientDetails.getClientId()); try { RestTemplate resTemplate = new
   * RestTemplate(); HttpHeaders headers = new HttpHeaders();
   * headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
   * headers.add("Accept", MediaType.APPLICATION_JSON_VALUE); String authValues =
   * clientDetails.getClientId() + ":" + clientDetails.getClientSecret(); String
   * base64EncodedString =
   * Base64.getEncoder().encodeToString(authValues.getBytes("utf-8"));
   * headers.add("Authorization", "Basic " + base64EncodedString);
   * MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
   * map.add(GRANT_TYPE, "client_credentials"); map.add("scope",
   * clientDetails.getScopes()); if (clientDetails.getRequireAud()) {
   * logger.info("Adding Aud Parameter while getting Accesstoken"); map.add("aud",
   * clientDetails.getFhirServerBaseURL()); } HttpEntity<MultiValueMap<String,
   * String>> entity = new HttpEntity<>(map, headers);
   *
   * ResponseEntity<?> response = resTemplate.exchange(
   * clientDetails.getTokenURL(), HttpMethod.POST, entity, Response.class);
   * tokenResponse = new JSONObject(response.getBody());
   * logger.info("Received AccessToken for Client: {}",
   * clientDetails.getClientId()); logger.info("Received AccessToken: {}",
   * tokenResponse);
   *
   * } catch (Exception e) { logger.error(
   * "Error in Getting the AccessToken for the client: {} ",
   * clientDetails.getClientId(), e); } return tokenResponse; }
   */
  public JSONObject getAccessTokenUsingClientDetails(ClientDetails clientDetails) {
    JSONObject tokenResponse = null;
    logger.trace("Getting AccessToken for Client: {}", clientDetails.getClientId());
    try {
      RestTemplate resTemplate = new RestTemplate();
      if (clientDetails.getIsSystem()) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.add(ACCEPT_HEADER, MediaType.APPLICATION_JSON_VALUE);
        String authValues = clientDetails.getClientId() + ":" + clientDetails.getClientSecret();
        String base64EncodedString =
            Base64.getEncoder().encodeToString(authValues.getBytes(StandardCharsets.UTF_8));
        headers.add("Authorization", "Basic " + base64EncodedString);
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add(GRANT_TYPE, "client_credentials");
        map.add("scope", clientDetails.getScopes());
        if (Boolean.TRUE.equals(clientDetails.getRequireAud())) {
          logger.debug("Adding Aud Parameter while getting Access token");
          map.add("aud", clientDetails.getFhirServerBaseURL());
        }
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(map, headers);

        ResponseEntity<?> response =
            resTemplate.exchange(
                clientDetails.getTokenURL(), HttpMethod.POST, entity, Response.class);
        tokenResponse = new JSONObject(response.getBody());
      } else if (clientDetails.getIsUserAccountLaunch()) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(clientDetails.getClientId(), clientDetails.getClientSecret());

        String tokenUrl =
            clientDetails.getTokenURL()
                + "?"
                + GRANT_TYPE
                + "="
                + CLIENT_CREDENTIALS
                + "&"
                + SCOPE
                + "="
                + clientDetails.getScopes();

        HttpEntity entity = new HttpEntity(headers);

        ResponseEntity<?> response =
            resTemplate.exchange(tokenUrl, HttpMethod.GET, entity, String.class);
        if (response.getStatusCode().is2xxSuccessful()) {
          String responseBody =
              response
                  .getBody()
                  .toString()
                  .replace(ACCESS_TOKEN_CAMEL_CASE, ACCESS_TOKEN)
                  .replace(EXPIRES_IN_CAMEL_CASE, EXPIRES_IN);
          tokenResponse = new JSONObject(responseBody);
        }
      }

      logger.trace("Received AccessToken for Client: {}", clientDetails.getClientId());

    } catch (Exception e) {
      logger.error(
          "Error in Getting the AccessToken for the client: {}", clientDetails.getClientId(), e);
    }
    return tokenResponse;
  }
}
