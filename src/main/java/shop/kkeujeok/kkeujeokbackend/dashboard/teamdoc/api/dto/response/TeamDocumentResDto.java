package shop.kkeujeok.kkeujeokbackend.dashboard.teamdoc.api.dto.response;

import lombok.Builder;
import shop.kkeujeok.kkeujeokbackend.dashboard.teamdoc.domain.TeamDocument;

@Builder
public record TeamDocumentResDto(
        String author,
        String picture,
        String title,
        String category,
        Long teamDocumentId
) {
    public static TeamDocumentResDto from(TeamDocument document) {
        return TeamDocumentResDto.builder()
                .author(document.getAuthor())
                .picture(document.getPicture())
                .title(document.getTitle())
                .category(document.getCategory())
                .teamDocumentId(document.getId())
                .build();
    }
}
