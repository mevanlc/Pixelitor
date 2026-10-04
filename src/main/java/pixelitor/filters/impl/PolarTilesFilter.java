/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 *
 * Pixelitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pixelitor. If not, see <http://www.gnu.org/licenses/>.
 */

package pixelitor.filters.impl;

import com.jhlabs.math.Noise;
import net.jafama.FastMath;
import pixelitor.filters.PolarTiles;

import java.awt.geom.Point2D;

import static com.jhlabs.image.ImageMath.INV_PI;

/**
 * The implementation of the {@link PolarTiles} filter. It distorts
 * the image in polar coordinates to form repeating radial tile structures.
 */
public class PolarTilesFilter extends CenteredTransformFilter {
    public static final int MODE_CONCENTRIC = 0;
    public static final int MODE_SPIRAL = 1;
    public static final int MODE_VORTEX = 2;
    public static final int MODE_VORTEX_TILES = 3; // vortex with counter-spirals
    public static final int MODE_FLOWER = 4; // pointed rose petals (|cos|), Rhodonea curve
    public static final int MODE_BRICK = 5; // staggered polar bricks (masonry / dome)
    public static final int MODE_WEB = 6; // regular N-sided polygonal rings (spiderweb / gem)

    private final double effectRotation;
    private final float randomness;
    private final double imageRotation;

    // precomputed factors for inner-loop performance
    private final double invZoom;
    private final double halfAngularDivisions;
    private final double angularCurvatureFactor;
    private final double radialCurvatureFactor;
    private final double radialAngleDivisions;
    private final double baseSpiralAngle;
    private final double spiralAngleFactor;
    private final double maxFlowerRadialArg;

    private final boolean hasAngularDistortion;
    private final boolean hasRadialDistortion;
    private final boolean hasFlowerDistortion;
    private final boolean hasAngularArg;
    private final boolean isVortexTiles;
    private final boolean isBrick;
    private final boolean isWeb;

    /**
     * Constructs a new PolarTilesFilter.
     *
     * @param filterName        the name of the filter.
     * @param edgeAction        the edge handling strategy (TRANSPARENT, REPEAT_EDGE, WRAP_AROUND, REFLECT).
     * @param interpolation     the interpolation method (NEAREST_NEIGHBOR, BILINEAR, BICUBIC).
     * @param center            the effect's center (in pixels).
     * @param mode              the tile mode (CONCENTRIC, SPIRAL, VORTEX, FLOWER, BRICK, WEB).
     * @param angularDivisions  the number of angular divisions (in VORTEX mode: number of spiral arms;
     *                          in FLOWER/STAR mode: number of petals/points; in WEB mode: polygon sides).
     * @param radialDivisions   the number of radial divisions (controls radial pitch / tiers).
     * @param curvature         the curvature factor of the glass tiles.
     * @param effectRotation    the rotation applied to the tile effect (0.0 to 1.0).
     * @param randomness        the amount of random noise displacement (0.0 to 1.0).
     * @param zoom              the image zoom percentage.
     * @param imageRotation     the rotation of the underlying image in radians.
     */
    public PolarTilesFilter(String filterName, int edgeAction, int interpolation, Point2D center,
                            int mode, int angularDivisions, int radialDivisions, double curvature,
                            double effectRotation, double randomness, double zoom, double imageRotation) {
        super(filterName, edgeAction, interpolation, center);

        assert zoom > 0.0;
        assert angularDivisions >= 0 && radialDivisions >= 0;

        // tan(x) has a period of π => the [0.0, 1.0] parameter
        // span covers a complete repeating cycle of the effect
        this.effectRotation = Math.PI * effectRotation;

        this.randomness = (float) (randomness * Math.PI);
        this.imageRotation = imageRotation;

        this.invZoom = 1.0 / zoom;
        double scaledCurvature = (curvature * curvature) * 0.1;
        this.angularCurvatureFactor = scaledCurvature * ((double) angularDivisions / 4);
        this.radialCurvatureFactor = scaledCurvature * ((double) radialDivisions / 2);
        this.halfAngularDivisions = angularDivisions * 0.5;
        this.radialAngleDivisions = Math.TAU * radialDivisions;
        this.baseSpiralAngle = Math.PI + this.effectRotation;

        // in FLOWER mode, limits petals to radialDivisions so that
        // higher-order partial petals do not fill the space between petals
        this.maxFlowerRadialArg = Math.PI * radialDivisions;

        this.hasFlowerDistortion = (mode == MODE_FLOWER) && (angularDivisions > 0);
        this.isVortexTiles = (mode == MODE_VORTEX_TILES);
        this.isBrick = (mode == MODE_BRICK) && (radialDivisions > 0);
        this.isWeb = (mode == MODE_WEB) && (angularDivisions >= 3);

        // vortex and flower modes do not use straight-line angular divisions
        this.hasAngularDistortion = switch (mode) {
            case MODE_CONCENTRIC, MODE_SPIRAL, MODE_VORTEX_TILES,
                 MODE_BRICK, MODE_WEB -> angularDivisions > 0 && angularCurvatureFactor != 0.0;
            case MODE_VORTEX, MODE_FLOWER -> false;
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        };

        this.hasRadialDistortion = radialDivisions > 0 && radialCurvatureFactor != 0.0;

        // in vortex modes, angularDivisions controls the number of spiral curves emerging from center
        this.spiralAngleFactor = switch (mode) {
            case MODE_SPIRAL -> (radialDivisions > 0) ? 0.5 : 0.0;
            case MODE_VORTEX, MODE_VORTEX_TILES -> (radialDivisions > 0) ? 0.5 * angularDivisions : 0.0;
            case MODE_CONCENTRIC, MODE_FLOWER, MODE_BRICK, MODE_WEB -> 0.0;
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        };

        this.hasAngularArg = this.hasAngularDistortion
            || this.hasFlowerDistortion
            || this.isVortexTiles
            || this.isWeb
            || this.isBrick;
    }

    @Override
    protected void transformInverse(int x, int y, float[] out) {
        double dx = x - cx;
        double dy = y - cy;
        double r2 = dx * dx + dy * dy;

        if (r2 == 0.0) {
            // (cx, cy) is the invariant center of scaling and rotation;
            // returning early also avoids division by zero
            out[0] = (float) cx;
            out[1] = (float) cy;
            return;
        }

        double radius = Math.sqrt(r2);
        double angle = FastMath.atan2(dy, dx);

        float noiseOffset = 0;
        if (randomness > 0) {
            noiseOffset = randomness * Noise.noise2((float) (dx / width), (float) (dy / height));
        }

        double angularArg = 0;
        if (hasAngularArg) {
            angularArg = noiseOffset + effectRotation + angle * halfAngularDivisions;
            if (isVortexTiles) {
                // modulate angular divisions along a counter-spiral to create diamond tiles
                angularArg -= (radius / width) * radialAngleDivisions;
            }

            if (isBrick) {
                // stagger alternate concentric tiers by half a tile (π / 2 in tan-space).
                // adding 0.5 * π aligns the tier shift with the radial tile boundaries (tan asymptotes)
                double tierPos = (radius / width) * radialAngleDivisions + 3 * noiseOffset;
                long tier = (long) FastMath.floor(tierPos * INV_PI + 0.5);
                if ((tier & 1L) != 0L) {
                    angularArg += 0.5 * Math.PI;
                }
            }
        }

        double distortedAngle = angle;
        if (hasAngularDistortion) {
            double angularTan = FastMath.tan(angularArg);
            double angularShift = (angularTan * angularCurvatureFactor) / radius;
            distortedAngle += angularShift;
        }

        if (hasRadialDistortion) {
            double radialArg = (radius / width) * radialAngleDivisions;
            boolean applyRadialDistortion = true;

            if (hasFlowerDistortion) {
                double cosA = FastMath.cos(angularArg);
                // pointed rose petals (Rhodonea curve) with V-creases meeting at center
                double petalFactor = 0.05 + 0.95 * Math.abs(cosA);
                radialArg /= petalFactor;

                // only generate petals up to radialDivisions; outside this range,
                // the space between and beyond petals remains undistorted
                applyRadialDistortion = radialArg <= maxFlowerRadialArg;
            } else if (isWeb) {
                // straight facet edges for regular N-sided polygons (spiderweb / cut gem)
                double roundK = Math.rint(angularArg * INV_PI);
                double psi = angularArg - roundK * Math.PI;
                double delta = psi / halfAngularDivisions;
                radialArg *= FastMath.cos(delta);
            } else if (spiralAngleFactor != 0.0) {
                radialArg += (baseSpiralAngle + angle) * spiralAngleFactor;
            }

            if (applyRadialDistortion) {
                double radialTan = FastMath.tan(3 * noiseOffset + radialArg);
                double radialShift = radialTan * radialCurvatureFactor;
                radius += radialShift;
            }
        }

        distortedAngle += imageRotation;

        double zoomedR = radius * invZoom;
        double u = zoomedR * FastMath.cos(distortedAngle);
        double v = zoomedR * FastMath.sin(distortedAngle);

        out[0] = (float) (u + cx);
        out[1] = (float) (v + cy);
    }
}
