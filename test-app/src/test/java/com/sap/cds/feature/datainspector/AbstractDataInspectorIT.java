/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-data-inspector contributors.
 */
package com.sap.cds.feature.datainspector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.feature.datainspector.testapp.Application;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Base class for the Data Inspector integration tests. Boots the full Spring context (SQLite + seed
 * data + both handlers) and exposes a security-enabled MockMvc plus basic-auth / JSON helpers.
 *
 * <p>Mock users (see application.yaml): alice has the read role (authorised), bob is authenticated
 * but unauthorised (403), no auth → 401.
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("default")
public abstract class AbstractDataInspectorIT {

  /** The OData V4 base path of the plugin's DataInspectorService. */
  protected static final String SERVICE_ENDPOINT = "/odata/v4/data-inspector";

  /** Mock user WITH the capDataInspectorReadonly role (see application.yaml). */
  protected static final String ALICE_USER = "alice";

  protected static final String ALICE_PASS = "alice";

  /** Mock user authenticated but WITHOUT the plugin's read role. */
  protected static final String BOB_USER = "bob";

  protected static final String BOB_PASS = "bob";

  @Autowired protected WebApplicationContext webApplicationContext;

  /** Deserialises OData JSON responses. */
  protected final ObjectMapper objectMapper = new ObjectMapper();

  protected MockMvc mockMvc;

  /**
   * Builds a security-enabled MockMvc once (reused across tests). @PostConstruct rather
   * than @BeforeEach avoids rebuilding it per method.
   */
  @PostConstruct
  void initMockMvc() {
    this.mockMvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
  }

  // ── Auth helpers ─────────────────────────────────────────────────────────────

  /**
   * GETs {@code path} (relative to {@link #SERVICE_ENDPOINT}, may include a query string) as the
   * given user (null = anonymous) and returns the raw result.
   *
   * <p>The query string is split into individual queryParam() calls so OData parameter names
   * ($filter, $top, …) reach the server un-encoded while values are safely encoded.
   */
  protected MvcResult get(String path, String user, String password) throws Exception {
    int q = path.indexOf('?');
    String bareUri = q < 0 ? path : path.substring(0, q);
    String queryString = q < 0 ? null : path.substring(q + 1);

    MockHttpServletRequestBuilder req =
        MockMvcRequestBuilders.get(SERVICE_ENDPOINT + bareUri).accept(MediaType.APPLICATION_JSON);

    if (queryString != null && !queryString.isEmpty()) {
      for (String pair : queryString.split("&")) {
        int eq = pair.indexOf('=');
        if (eq < 0) {
          req = req.queryParam(pair, "");
        } else {
          req = req.queryParam(pair.substring(0, eq), pair.substring(eq + 1));
        }
      }
    }

    if (user != null) {
      req = req.with(SecurityMockMvcRequestPostProcessors.httpBasic(user, password));
    }
    return mockMvc.perform(req).andReturn();
  }

  /** Convenience: GET as alice (authorised user). */
  protected MvcResult getAsAlice(String path) throws Exception {
    return get(path, ALICE_USER, ALICE_PASS);
  }

  /** Convenience: GET as bob (authenticated but not authorised). */
  protected MvcResult getAsBob(String path) throws Exception {
    return get(path, BOB_USER, BOB_PASS);
  }

  /** Convenience: GET without authentication. */
  protected MvcResult getAnonymous(String path) throws Exception {
    return get(path, null, null);
  }

  // ── Response parsing helpers ─────────────────────────────────────────────────

  /** Reads a MockMvc response body as JSON (UTF-8, to preserve non-ASCII data). */
  protected JsonNode json(MvcResult result) throws Exception {
    byte[] bytes = result.getResponse().getContentAsByteArray();
    String body = new String(bytes, StandardCharsets.UTF_8);
    return objectMapper.readTree(body);
  }

  /** GET as alice and parse the JSON response; fails the test on non-2xx. */
  protected JsonNode getJsonAsAlice(String path) throws Exception {
    MvcResult result = getAsAlice(path);
    int status = result.getResponse().getStatus();
    if (status < 200 || status >= 300) {
      throw new AssertionError(
          "Expected 2xx but got "
              + status
              + " for GET "
              + path
              + " — body: "
              + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    return json(result);
  }
}
