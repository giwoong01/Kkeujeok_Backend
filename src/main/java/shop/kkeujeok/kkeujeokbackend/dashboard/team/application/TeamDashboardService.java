package shop.kkeujeok.kkeujeokbackend.dashboard.team.application;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shop.kkeujeok.kkeujeokbackend.dashboard.exception.DashboardDeletedException;
import shop.kkeujeok.kkeujeokbackend.dashboard.exception.DashboardNotFoundException;
import shop.kkeujeok.kkeujeokbackend.dashboard.exception.InvalidMemberInviteException;
import shop.kkeujeok.kkeujeokbackend.dashboard.exception.UnauthorizedAccessException;
import shop.kkeujeok.kkeujeokbackend.dashboard.personal.exception.DashboardAccessDeniedException;
import shop.kkeujeok.kkeujeokbackend.dashboard.team.api.dto.request.TeamDashboardSaveReqDto;
import shop.kkeujeok.kkeujeokbackend.dashboard.team.api.dto.request.TeamDashboardUpdateReqDto;
import shop.kkeujeok.kkeujeokbackend.dashboard.team.api.dto.response.SearchMemberListResDto;
import shop.kkeujeok.kkeujeokbackend.dashboard.team.api.dto.response.TeamDashboardInfoResDto;
import shop.kkeujeok.kkeujeokbackend.dashboard.team.api.dto.response.TeamDashboardListResDto;
import shop.kkeujeok.kkeujeokbackend.dashboard.team.domain.TeamDashboard;
import shop.kkeujeok.kkeujeokbackend.dashboard.team.domain.repository.TeamDashboardRepository;
import shop.kkeujeok.kkeujeokbackend.dashboard.team.exception.AlreadyJoinedTeamException;
import shop.kkeujeok.kkeujeokbackend.dashboard.team.exception.NotMemberOfTeamException;
import shop.kkeujeok.kkeujeokbackend.global.dto.PageInfoResDto;
import shop.kkeujeok.kkeujeokbackend.member.domain.Member;
import shop.kkeujeok.kkeujeokbackend.member.domain.repository.MemberRepository;
import shop.kkeujeok.kkeujeokbackend.member.exception.MemberNotFoundException;
import shop.kkeujeok.kkeujeokbackend.notification.application.NotificationService;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamDashboardService {

    private static final String TEAM_DASHBOARD_JOIN_MESSAGE = "팀 대시보드 초대: %s님이 %s 대시보드에 초대하였습니다.teamdashbord%d";
    private static final String TEAM_JOIN_ACCEPT_MESSAGE = "팀 초대 수락: %s님이 초대를 수락하였습니다.";

    private final TeamDashboardRepository teamDashboardRepository;
    private final MemberRepository memberRepository;
    private final NotificationService notificationService;

    // 팀 대시보드 저장
    @Transactional
    public TeamDashboardInfoResDto save(String email, TeamDashboardSaveReqDto teamDashboardSaveReqDto) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        TeamDashboard teamDashboard = teamDashboardSaveReqDto.toEntity(member);

        teamDashboardRepository.save(teamDashboard);

        inviteMember(member, teamDashboard,
                teamDashboardSaveReqDto.invitedEmails(),
                teamDashboardSaveReqDto.invitedNicknamesAndTags());
        return TeamDashboardInfoResDto.of(member, teamDashboard);
    }

    // 팀 대시보드 수정
    @Transactional
    public TeamDashboardInfoResDto update(String email,
                                          Long dashboardId,
                                          TeamDashboardUpdateReqDto teamDashboardUpdateReqDto) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        TeamDashboard dashboard = teamDashboardRepository.findById(dashboardId)
                .orElseThrow(DashboardNotFoundException::new);

        verifyMemberIsAuthor(dashboard, member);

        dashboard.update(teamDashboardUpdateReqDto.title(),
                teamDashboardUpdateReqDto.description());

        inviteMember(member, dashboard,
                teamDashboardUpdateReqDto.invitedEmails(),
                teamDashboardUpdateReqDto.invitedNicknamesAndTags());

        return TeamDashboardInfoResDto.of(member, dashboard);
    }

    // 팀 대시보드 전체 조회(페이지네이션)
    public TeamDashboardListResDto findForTeamDashboard(String email, Pageable pageable) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        Page<TeamDashboard> teamDashboards = teamDashboardRepository.findForTeamDashboard(member, pageable);

        List<TeamDashboardInfoResDto> teamDashboardInfoResDtoList = teamDashboards.stream()
                .map(t -> TeamDashboardInfoResDto.of(member, t))
                .toList();

        return TeamDashboardListResDto
                .of(teamDashboardInfoResDtoList, PageInfoResDto.from(teamDashboards));
    }

    // 팀 대시보드 전체 조회(페이지네이션 X)
    public TeamDashboardListResDto findForTeamDashboard(String email) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        List<TeamDashboard> teamDashboards = teamDashboardRepository.findForTeamDashboard(member);

        List<TeamDashboardInfoResDto> teamDashboardInfoResDtoList = teamDashboards.stream()
                .map(t -> TeamDashboardInfoResDto.of(member, t))
                .toList();

        return TeamDashboardListResDto.from(teamDashboardInfoResDtoList);
    }

    // 팀 대시보드 상세 조회
    public TeamDashboardInfoResDto findById(String email, Long dashboardId) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        TeamDashboard dashboard = teamDashboardRepository.findById(dashboardId)
                .orElseThrow(DashboardNotFoundException::new);

        checkIfDashboardIsDeleted(dashboard);
        validateDashboardAccess(dashboard, member);

        double blockProgress = teamDashboardRepository.calculateCompletionPercentage(dashboard.getId());

        return TeamDashboardInfoResDto.detailOf(member, dashboard, blockProgress);
    }

    private void checkIfDashboardIsDeleted(TeamDashboard dashboard) {
        if (dashboard.isDeleted()) {
            throw new DashboardDeletedException();
        }
    }

    private void validateDashboardAccess(TeamDashboard dashboard, Member member) {
        boolean isMemberInDashboard = dashboard.getTeamDashboardMemberMappings().stream()
                .anyMatch(mapping -> mapping.getMember().equals(member));

        if (!dashboard.getMember().equals(member) && !isMemberInDashboard) {
            throw new UnauthorizedAccessException();
        }
    }

    // 팀 대시보드 삭제 유무 업데이트 (논리 삭제)
    @Transactional
    public void delete(String email, Long dashboardId) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        TeamDashboard dashboard = teamDashboardRepository.findById(dashboardId)
                .orElseThrow(DashboardNotFoundException::new);

        verifyMemberIsAuthor(dashboard, member);

        dashboard.statusUpdate();
    }

    @Transactional
    public void joinTeam(String email, Long dashboardId) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        TeamDashboard dashboard = teamDashboardRepository.findById(dashboardId)
                .orElseThrow(DashboardNotFoundException::new);

        validateMemberNotAlreadyJoined(dashboard, member);

        dashboard.addMember(member);

        String message = String.format(TEAM_JOIN_ACCEPT_MESSAGE, member.getEmail());
        notificationService.sendNotification(dashboard.getMember(), message);
    }

    private void validateMemberNotAlreadyJoined(TeamDashboard dashboard, Member member) {
        if (dashboard.hasMember(member)) {
            throw new AlreadyJoinedTeamException();
        }
    }

    @Transactional
    public void leaveTeam(String email, Long dashboardId) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        TeamDashboard dashboard = teamDashboardRepository.findById(dashboardId)
                .orElseThrow(DashboardNotFoundException::new);

        validateMemberInTeam(dashboard, member);

        dashboard.removeMember(member);
    }

    private void validateMemberInTeam(TeamDashboard dashboard, Member member) {
        if (!dashboard.hasMember(member)) {
            throw new NotMemberOfTeamException();
        }
    }

    public SearchMemberListResDto searchMembers(String query) {
        List<Member> searchMembers = teamDashboardRepository.findForMembersByQuery(query);

        return SearchMemberListResDto.from(searchMembers);
    }

    private void inviteMember(Member member, TeamDashboard teamDashboard,
                              List<String> invitedEmails,
                              List<String> invitedNicknamesAndTags) {
        for (String email : invitedEmails) {
            try {
                verifyIsSameEmail(member.getEmail(), email);

                Member inviteReceivedMember = memberRepository.findByEmail(email)
                        .orElseThrow(MemberNotFoundException::new);

                String message = String.format(TEAM_DASHBOARD_JOIN_MESSAGE, member.getName(),
                        teamDashboard.getTitle(), teamDashboard.getId());
                notificationService.sendNotification(inviteReceivedMember, message);
            } catch (MemberNotFoundException ignored) {
            }
        }

        for (String nicknameAndTag : invitedNicknamesAndTags) {
            try {
                String[] split = nicknameAndTag.split("#");
                String nickname = split[0];
                String tag = "#" + split[1];

                Member inviteReceivedMember = memberRepository.findByNicknameAndTag(nickname, tag)
                        .orElseThrow(MemberNotFoundException::new);

                String message = String.format(TEAM_DASHBOARD_JOIN_MESSAGE, member.getName(),
                        teamDashboard.getTitle(), teamDashboard.getId());
                notificationService.sendNotification(inviteReceivedMember, message);
            } catch (MemberNotFoundException ignored) {
            }
        }
    }

    private void verifyIsSameEmail(String email, String otherEmail) {
        if (email.equals(otherEmail)) {
            throw new InvalidMemberInviteException();
        }
    }

    private void verifyMemberIsAuthor(TeamDashboard teamDashboard, Member member) {
        if (!member.equals(teamDashboard.getMember())) {
            throw new DashboardAccessDeniedException();
        }
    }
}
