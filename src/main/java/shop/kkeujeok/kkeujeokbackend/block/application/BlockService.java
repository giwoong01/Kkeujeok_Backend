package shop.kkeujeok.kkeujeokbackend.block.application;

import static java.time.temporal.ChronoUnit.DAYS;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shop.kkeujeok.kkeujeokbackend.block.api.dto.request.BlockSaveReqDto;
import shop.kkeujeok.kkeujeokbackend.block.api.dto.request.BlockSequenceUpdateReqDto;
import shop.kkeujeok.kkeujeokbackend.block.api.dto.request.BlockUpdateReqDto;
import shop.kkeujeok.kkeujeokbackend.block.api.dto.response.BlockInfoResDto;
import shop.kkeujeok.kkeujeokbackend.block.api.dto.response.BlockListResDto;
import shop.kkeujeok.kkeujeokbackend.block.application.util.DDayCalculator;
import shop.kkeujeok.kkeujeokbackend.block.domain.Block;
import shop.kkeujeok.kkeujeokbackend.block.domain.Progress;
import shop.kkeujeok.kkeujeokbackend.block.domain.Type;
import shop.kkeujeok.kkeujeokbackend.block.domain.repository.BlockRepository;
import shop.kkeujeok.kkeujeokbackend.block.exception.BlockNotFoundException;
import shop.kkeujeok.kkeujeokbackend.block.exception.InvalidProgressException;
import shop.kkeujeok.kkeujeokbackend.challenge.domain.Challenge;
import shop.kkeujeok.kkeujeokbackend.dashboard.domain.Dashboard;
import shop.kkeujeok.kkeujeokbackend.dashboard.domain.repository.DashboardRepository;
import shop.kkeujeok.kkeujeokbackend.dashboard.exception.DashboardNotFoundException;
import shop.kkeujeok.kkeujeokbackend.dashboard.exception.UnauthorizedAccessException;
import shop.kkeujeok.kkeujeokbackend.dashboard.personal.domain.PersonalDashboard;
import shop.kkeujeok.kkeujeokbackend.dashboard.team.domain.TeamDashboard;
import shop.kkeujeok.kkeujeokbackend.global.dto.PageInfoResDto;
import shop.kkeujeok.kkeujeokbackend.member.domain.Member;
import shop.kkeujeok.kkeujeokbackend.member.domain.repository.MemberRepository;
import shop.kkeujeok.kkeujeokbackend.member.exception.MemberNotFoundException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlockService {

    private final MemberRepository memberRepository;
    private final BlockRepository blockRepository;
    private final DashboardRepository dashboardRepository;
    private final RedisTemplate<String, String> redisTemplate;

    // 블록 생성
    @Transactional
    public BlockInfoResDto save(String email, BlockSaveReqDto blockSaveReqDto) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        Dashboard dashboard = dashboardRepository.findById(blockSaveReqDto.dashboardId())
                .orElseThrow(DashboardNotFoundException::new);

        validateDashboardAccess(dashboard, member);

        int lastSequence = blockRepository.findLastSequenceByProgress(
                member,
                dashboard.getId(),
                blockSaveReqDto.progress());

        Block block = blockRepository.save(blockSaveReqDto.toEntity(member, dashboard, lastSequence));
        saveDeadlineNotification(block);

        return BlockInfoResDto.from(block, DDayCalculator.calculate(block.getDeadLine()));
    }

    private void saveDeadlineNotification(Block block) {
        String deadlineKey = "block:deadline:" + block.getId();

        String memberEmail = block.getMember().getEmail();
        String deadlineValue = block.getDeadLine() + ":" + memberEmail + ":" + block.getTitle();
        redisTemplate.opsForValue().set(deadlineKey, deadlineValue);

        LocalDateTime deadline = LocalDateTime.parse(block.getDeadLine(),
                DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"));

        long daysToDeadline = DAYS.between(LocalDate.now(), deadline.toLocalDate().minusDays(1));
        redisTemplate.expire(deadlineKey, Duration.ofDays(daysToDeadline));
    }


    // 블록 수정 (자동 수정 예정)
    @Transactional
    public BlockInfoResDto update(String email, Long blockId, BlockUpdateReqDto blockUpdateReqDto) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        Block block = blockRepository.findById(blockId).orElseThrow(BlockNotFoundException::new);
        Dashboard dashboard = dashboardRepository.findById(block.getDashboard().getId())
                .orElseThrow(DashboardNotFoundException::new);

        validateDashboardAccess(dashboard, member);

        block.update(blockUpdateReqDto.title(),
                blockUpdateReqDto.contents(),
                blockUpdateReqDto.startDate(),
                blockUpdateReqDto.deadLine());

        return BlockInfoResDto.from(block, DDayCalculator.calculate(block.getDeadLine()));
    }

    // 블록 상태 업데이트 (Progress)
    @Transactional
    public BlockInfoResDto progressUpdate(String email, Long blockId, String progressString,
                                          BlockSequenceUpdateReqDto blockSequenceUpdateReqDto) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        Block block = blockRepository.findById(blockId).orElseThrow(BlockNotFoundException::new);

        Progress progress = parseProgress(progressString);

        Dashboard dashboard = dashboardRepository.findById(blockSequenceUpdateReqDto.dashboardId())
                .orElseThrow(DashboardNotFoundException::new);

        validateDashboardAccess(dashboard, member);

        block.progressUpdate(progress);

        updateChallengeCompletedMemberByProgress(block, member, progress);

        updateBlockSequence(member, blockSequenceUpdateReqDto.notStartedList(), dashboard.getId(),
                Progress.NOT_STARTED);
        updateBlockSequence(member, blockSequenceUpdateReqDto.inProgressList(), dashboard.getId(),
                Progress.IN_PROGRESS);
        updateBlockSequence(member, blockSequenceUpdateReqDto.completedList(), dashboard.getId(), Progress.COMPLETED);

        return BlockInfoResDto.from(block, DDayCalculator.calculate(block.getDeadLine()));
    }

    private void updateChallengeCompletedMemberByProgress(Block block, Member member, Progress progress) {
        if (block.getType() == Type.CHALLENGE) {
            Challenge challenge = block.getChallenge();
            challenge.updateCompletedMember(member, progress);
        }
    }

    public BlockListResDto findForBlockByProgress(Long dashboardId, String progress, Pageable pageable) {
        Page<BlockInfoResDto> blocks = blockRepository.findForBlockByProgress(
                dashboardId,
                parseProgress(progress),
                pageable);

        return BlockListResDto.from(blocks.stream().toList(), PageInfoResDto.from(blocks));
    }

    // 블록 상세보기
    public BlockInfoResDto findById(String email, Long blockId) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        Block block = blockRepository.findById(blockId).orElseThrow(BlockNotFoundException::new);

        return BlockInfoResDto.from(block, DDayCalculator.calculate(block.getDeadLine()));
    }

    // 블록 삭제 유무 업데이트 (논리 삭제)
    @Transactional
    public void delete(String email, Long blockId) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        Block block = blockRepository.findById(blockId).orElseThrow(BlockNotFoundException::new);
        Dashboard dashboard = dashboardRepository.findById(block.getDashboard().getId())
                .orElseThrow(DashboardNotFoundException::new);

        validateDashboardAccess(dashboard, member);

        block.statusUpdate();
    }

    // 블록 순번 변경
    @Transactional
    public void changeBlocksSequence(String email, BlockSequenceUpdateReqDto blockSequenceUpdateReqDto) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        Long dashboardId = blockSequenceUpdateReqDto.dashboardId();
        Dashboard dashboard = dashboardRepository.findById(blockSequenceUpdateReqDto.dashboardId())
                .orElseThrow(DashboardNotFoundException::new);

        validateDashboardAccess(dashboard, member);

        updateBlockSequence(member, blockSequenceUpdateReqDto.notStartedList(), dashboardId, Progress.NOT_STARTED);
        updateBlockSequence(member, blockSequenceUpdateReqDto.inProgressList(), dashboardId, Progress.IN_PROGRESS);
        updateBlockSequence(member, blockSequenceUpdateReqDto.completedList(), dashboardId, Progress.COMPLETED);
    }

    private void updateBlockSequence(Member member, List<Long> blockIds, Long dashboardId, Progress progress) {
        int lastSequence = blockRepository.findLastSequenceByProgress(
                member,
                dashboardId,
                progress);

        for (Long blockId : blockIds) {
            Block block = blockRepository.findById(blockId).orElseThrow(BlockNotFoundException::new);

            if (block.getSequence() != lastSequence) {
                block.sequenceUpdate(lastSequence);
            }

            lastSequence--;
        }
    }

    // 삭제된 블록 조회
    public BlockListResDto findDeletedBlocks(String email, Long dashboardId, Pageable pageable) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        Dashboard dashboard = dashboardRepository.findById(dashboardId).orElseThrow(DashboardNotFoundException::new);

        validateDashboardAccess(dashboard, member);

        Page<Block> deletedBlocks = blockRepository.findByDeletedBlocks(dashboardId, pageable);

        List<BlockInfoResDto> blockInfoResDtoList = deletedBlocks.stream()
                .map(block -> BlockInfoResDto.from(block, DDayCalculator.calculate(block.getDeadLine())))
                .toList();

        return BlockListResDto.from(blockInfoResDtoList, PageInfoResDto.from(deletedBlocks));
    }

    // 블록 영구 삭제
    @Transactional
    public void deletePermanently(String email, Long blockId) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        Block block = blockRepository.findById(blockId).orElseThrow(BlockNotFoundException::new);
        Dashboard dashboard = dashboardRepository.findById(block.getDashboard().getId())
                .orElseThrow(DashboardNotFoundException::new);

        validateDashboardAccess(dashboard, member);

        blockRepository.delete(block);
    }

    // 삭제된 블록 전체 삭제
    @Transactional
    public void deleteAllPermanently(String email, Long dashboardId) {
        Member member = memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
        Dashboard dashboard = dashboardRepository.findById(dashboardId).orElseThrow(DashboardNotFoundException::new);

        validateDashboardAccess(dashboard, member);

        List<Block> deletedBlocks = blockRepository.findByDeletedBlocks(dashboardId);

        blockRepository.deleteAll(deletedBlocks);
    }

    private Progress parseProgress(String progressString) {
        try {
            return Progress.valueOf(progressString);
        } catch (IllegalArgumentException e) {
            throw new InvalidProgressException();
        }
    }

    private void validateDashboardAccess(Dashboard dashboard, Member member) {
        if (dashboard instanceof PersonalDashboard) {
            validatePersonalDashboardAccess((PersonalDashboard) dashboard, member);
        }

        if (dashboard instanceof TeamDashboard) {
            validateTeamDashboardAccess((TeamDashboard) dashboard, member);
        }
    }

    private void validatePersonalDashboardAccess(PersonalDashboard dashboard, Member member) {
        if (!dashboard.getMember().getEmail().equals(member.getEmail())) {
            throw new UnauthorizedAccessException();
        }
    }

    private void validateTeamDashboardAccess(TeamDashboard dashboard, Member member) {
        boolean isMemberInDashboard = dashboard.getTeamDashboardMemberMappings().stream()
                .anyMatch(mapping -> mapping.getMember().equals(member));

        if (!dashboard.getMember().equals(member) && !isMemberInDashboard) {
            throw new UnauthorizedAccessException();
        }
    }

}
