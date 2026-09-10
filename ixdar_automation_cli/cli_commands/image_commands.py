"""Screenshot inspection commands: compare two captures, or check one for a blank render."""

from typing import Annotated

from ..cli_registry import CliCommandResult, CliOption, cli_command
from ..png_image import compare_images, image_statistics, read_png

Positional = CliOption(positional=True)

DEFAULT_FUZZ = 8


@cli_command(name="image-diff")
def image_diff(
    first: Annotated[str, Positional],
    second: Annotated[str, Positional],
    fuzz: int = DEFAULT_FUZZ,
    max_differing: int = -1,
) -> CliCommandResult:
    """Compare two PNG screenshots, reporting RMSE and how many pixels differ beyond a fuzz.

    Identical images report zero differing pixels and zero RMSE, so this replaces comparing
    sha256 digests, which cannot say how large a difference is.

    :param first: Path to the baseline PNG.
    :param second: Path to the PNG being compared against the baseline.
    :param fuzz: Per-channel value a pixel may differ by before it counts as changed.
    :param max_differing: Fail (exit 6) when more pixels than this differ; negative never fails.
    """
    comparison = compare_images(read_png(first), read_png(second), fuzz)
    comparison["first"] = str(first)
    comparison["second"] = str(second)
    comparison["fuzz"] = fuzz
    exceeded = 0 <= max_differing < comparison["differingPixels"]
    comparison["ok"] = not exceeded
    return CliCommandResult(payload=comparison, exit_code=6 if exceeded else 0)


@cli_command(name="image-stats")
def image_stats(
    image: Annotated[str, Positional],
    min_mean: float = -1.0,
) -> CliCommandResult:
    """Report a PNG's mean, minimum and maximum channel values and whether it is a blank frame.

    A render that came out entirely one colour — the all-black multiview composite, a screenshot
    taken before the scene drew — is a blank frame, and a blank frame fails.

    :param image: Path to the PNG to measure.
    :param min_mean: Fail (exit 6) when the mean channel value is below this; negative only fails on a blank frame.
    """
    statistics = image_statistics(read_png(image))
    statistics["path"] = str(image)
    too_dark = min_mean >= 0 and statistics["meanChannelValue"] < min_mean
    statistics["ok"] = not statistics["blank"] and not too_dark
    return CliCommandResult(payload=statistics, exit_code=0 if statistics["ok"] else 6)
