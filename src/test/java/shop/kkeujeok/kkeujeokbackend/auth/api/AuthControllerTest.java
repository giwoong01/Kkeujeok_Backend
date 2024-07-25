package shop.kkeujeok.kkeujeokbackend.auth.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import shop.kkeujeok.kkeujeokbackend.auth.api.AuthController;
import shop.kkeujeok.kkeujeokbackend.auth.api.dto.request.RefreshTokenReqDto;
import shop.kkeujeok.kkeujeokbackend.auth.api.dto.request.TokenReqDto;
import shop.kkeujeok.kkeujeokbackend.auth.api.dto.response.MemberLoginResDto;
import shop.kkeujeok.kkeujeokbackend.auth.api.dto.response.UserInfo;
import shop.kkeujeok.kkeujeokbackend.auth.application.AuthMemberService;
import shop.kkeujeok.kkeujeokbackend.auth.application.AuthService;
import shop.kkeujeok.kkeujeokbackend.auth.application.AuthServiceFactory;
import shop.kkeujeok.kkeujeokbackend.auth.application.TokenService;
import shop.kkeujeok.kkeujeokbackend.global.jwt.api.dto.TokenDto;
import shop.kkeujeok.kkeujeokbackend.member.domain.Member;
import shop.kkeujeok.kkeujeokbackend.member.domain.SocialType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(AuthController.class)
public class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthServiceFactory authServiceFactory;

    @MockBean
    private AuthMemberService authMemberService;

    @MockBean
    private TokenService tokenService;

    @Mock
    private AuthService authService;

    private Member member;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        member = Member.builder()
                .email("email")
                .nickname("nickname")
                .socialType(SocialType.GOOGLE)
                .build();
    }


    @Test
    public void generateAccessAndRefreshToken_ShouldReturnToken() throws Exception {
        String provider = "google";
        TokenReqDto tokenReqDto = new TokenReqDto("auth-code");
        UserInfo userInfo = new UserInfo("email", "name", "picture", "nickname");
        MemberLoginResDto memberLoginResDto = MemberLoginResDto.from(member);
        TokenDto tokenDto = new TokenDto("new-access-token", "new-refresh-token");

        given(authServiceFactory.getAuthService(provider)).willReturn(authService);
        given(authService.getUserInfo(any(String.class))).willReturn(userInfo);
        given(authMemberService.saveUserInfo(any(UserInfo.class), any(SocialType.class))).willReturn(memberLoginResDto);
        given(tokenService.getToken(any(MemberLoginResDto.class))).willReturn(tokenDto);

        mockMvc.perform(post("/api/google/token")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tokenReqDto)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(HttpStatus.OK.value()))
                .andExpect(jsonPath("$.message").value("토큰 발급"))
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("new-refresh-token"));
    }

    @DisplayName("리프레쉬 토큰으로 액세스 토큰을 발급합니다.")
    @Test
    public void 리프레쉬_토큰으로_액세스_토큰을_발급합니다() throws Exception {
        RefreshTokenReqDto refreshTokenReqDto = new RefreshTokenReqDto("test-refresh-token");
        TokenDto tokenDto = new TokenDto("new-access-token", "test-refresh-token");

        given(tokenService.generateAccessToken(any(RefreshTokenReqDto.class))).willReturn(tokenDto);

        mockMvc.perform(post("/api/token/access")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshTokenReqDto)))
                .andDo(print())
                .andExpect(status().isOk())  // Expecting 200 OK status
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("액세스 토큰 발급"))
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("test-refresh-token"));
    }
}
