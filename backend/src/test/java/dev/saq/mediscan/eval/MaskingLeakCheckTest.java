package dev.saq.mediscan.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.saq.mediscan.eval.ExpectedResult.PiiType;
import dev.saq.mediscan.eval.ExpectedResult.PlantedPii;
import dev.saq.mediscan.eval.MaskingLeakCheck.Haystack;

/** Hand-made inputs, per {@code docs/phase-3-lld.md} section 9, item 3. */
class MaskingLeakCheckTest {

	@Test
	void findsAPlainSubstringLeak() {
		var planted = List.of(new PlantedPii(PiiType.NAME, "Jane Roe"));
		var haystacks = List.of(new Haystack("masked_text", "Patient Name: Jane Roe"));

		var leaks = MaskingLeakCheck.findLeaks(planted, haystacks);

		assertThat(leaks).extracting(MaskingLeakCheck.Leak::text).containsExactly("Jane Roe");
	}

	@Test
	void findsNothingWhenThePlaceholderSurvived() {
		var planted = List.of(new PlantedPii(PiiType.NAME, "Jane Roe"));
		var haystacks = List.of(new Haystack("masked_text", "Patient Name: [NAME]"));

		assertThat(MaskingLeakCheck.findLeaks(planted, haystacks)).isEmpty();
	}

	@Test
	void matchesAPhoneAcrossDifferentFormatting() {
		var planted = List.of(new PlantedPii(PiiType.PHONE, "(555) 123-4567"));
		var haystacks = List.of(new Haystack("masked_text", "call 555.123.4567 for results"));

		var leaks = MaskingLeakCheck.findLeaks(planted, haystacks);

		assertThat(leaks).hasSize(1);
		assertThat(leaks.get(0).type()).isEqualTo(PiiType.PHONE);
	}

	@Test
	void ignoresAPhoneThatIsOnlyPartiallyPresent() {
		// Different number: digits don't form the same 10-digit run anywhere.
		var planted = List.of(new PlantedPii(PiiType.PHONE, "(555) 123-4567"));
		var haystacks = List.of(new Haystack("masked_text", "call 555.999.0000 instead"));

		assertThat(MaskingLeakCheck.findLeaks(planted, haystacks)).isEmpty();
	}

	@Test
	void catchesABareLastNameWithNoFirstName() {
		var planted = List.of(new PlantedPii(PiiType.NAME, "Jane Roe"));
		var haystacks = List.of(new Haystack("report_summary", "Reviewed by Roe on file"));

		var leaks = MaskingLeakCheck.findLeaks(planted, haystacks);

		assertThat(leaks).hasSize(1);
	}

	@Test
	void ignoresNamePartsShorterThanThreeCharacters() {
		// "Jo Li" has two parts, both under the 3-character floor, so a coincidental "li"
		// elsewhere in the text must not count as a leak.
		var planted = List.of(new PlantedPii(PiiType.NAME, "Jo Li"));
		var haystacks = List.of(new Haystack("masked_text", "Lipid panel results below"));

		assertThat(MaskingLeakCheck.findLeaks(planted, haystacks)).isEmpty();
	}

	@Test
	void stillDetectsAnAddressLeak() {
		// E6: ADDRESS is excluded from the zero-leak target by the caller's scoring, not by
		// this class refusing to look for it.
		var planted = List.of(new PlantedPii(PiiType.ADDRESS, "719 Larkspur Court"));
		var haystacks = List.of(new Haystack("masked_text", "Home Address: 719 Larkspur Court"));

		var leaks = MaskingLeakCheck.findLeaks(planted, haystacks);

		assertThat(leaks).extracting(MaskingLeakCheck.Leak::type).containsExactly(PiiType.ADDRESS);
	}

	@Test
	void checksEveryHaystackAndReportsWhereItFoundTheFirstOne() {
		var planted = List.of(new PlantedPii(PiiType.ID, "PT-12345"));
		var haystacks = List.of(
				new Haystack("masked_text", "clean, no id here"),
				new Haystack("llm_request[extract]", "Patient ID: PT-12345"));

		var leaks = MaskingLeakCheck.findLeaks(planted, haystacks);

		assertThat(leaks).hasSize(1);
		assertThat(leaks.get(0).where()).isEqualTo("llm_request[extract]");
	}

	@Test
	void aNullHaystackIsTreatedAsAbsent() {
		var planted = List.of(new PlantedPii(PiiType.EMAIL, "jane@example.test"));
		var haystacks = List.of(new Haystack("report_summary", null));

		assertThat(MaskingLeakCheck.findLeaks(planted, haystacks)).isEmpty();
	}
}
