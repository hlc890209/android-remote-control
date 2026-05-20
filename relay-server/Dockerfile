FROM node:18-alpine
WORKDIR /app
COPY package.json index.js ./
RUN npm install --production
EXPOSE 8080
ENV AUTH_TOKEN=zyyh_remote_test_2024
CMD ["node", "index.js"]
