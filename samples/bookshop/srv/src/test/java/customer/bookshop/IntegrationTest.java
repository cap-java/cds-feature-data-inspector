package customer.bookshop;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.CoreMatchers.containsString;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class IntegrationTest {
    private static final String bookURI = "/odata/v4/CatalogService/Books";
    private static final String bookID = "7756b725-cefc-43a2-a3c8-0c9104a349b8";
    
    @Autowired MockMvc mockMvc;

    @Test
    @WithMockUser(username = "user", password = "user")
    public void emojiAppended() throws Exception {
        mockMvc.perform(get(bookURI+("(ID="+bookID+")")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").exists())
            .andExpect(jsonPath("$.title").value(containsString("🙃")));
    }
}
