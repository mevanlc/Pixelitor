/*
Copyright 2006 Jerry Huxtable

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.jhlabs.image;

import java.util.concurrent.ThreadLocalRandom;

/**
 * This filter diffuses an image by moving its pixels in random directions.
 */
public class DiffuseFilter extends TransformFilter {
    public static final int TABLE_SIZE = 256;

    private final float[] sinTable;
    private final float[] cosTable;

    /**
     * Creates a diffuse filter with the given parameters.
     *
     * @param filterName   the name of the filter
     * @param amount       the maximum amount of the diffusion
     * @param edgeAction   the action to take for pixels outside the image bounds
     * @param interpolation the interpolation method to use
     */
    public DiffuseFilter(String filterName, float amount, int edgeAction, int interpolation) {
        super(filterName, edgeAction, interpolation);

        sinTable = new float[TABLE_SIZE];
        cosTable = new float[TABLE_SIZE];

        for (int i = 0; i < TABLE_SIZE; i++) {
            double angle = Math.TAU * i / TABLE_SIZE;
            sinTable[i] = (float) (amount * Math.sin(angle));
            cosTable[i] = (float) (amount * Math.cos(angle));
        }
    }

    @Override
    protected void transformInverse(int x, int y, float[] out) {
        var rng = ThreadLocalRandom.current();
        int angle = rng.nextInt(TABLE_SIZE);
        float distance = rng.nextFloat();

        out[0] = x + distance * sinTable[angle];
        out[1] = y + distance * cosTable[angle];
    }
}
